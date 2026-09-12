package com.urban.script.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urban.script.common.BusinessException;
import com.urban.script.common.RedisKeyConstant;
import com.urban.script.common.ResultCode;
import com.urban.script.shop.dto.SessionCreateReq;
import com.urban.script.shop.dto.SessionFeignRes;
import com.urban.script.shop.dto.SessionRes;
import com.urban.script.shop.entity.ScriptInfo;
import com.urban.script.shop.entity.SessionInfo;
import com.urban.script.shop.entity.ShopInfo;
import com.urban.script.shop.mapper.ScriptMapper;
import com.urban.script.shop.mapper.SessionMapper;
import com.urban.script.shop.mapper.ShopMapper;
import com.urban.script.shop.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 场次服务实现
 *
 * @author urban-script-reservation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionServiceImpl implements SessionService {

    private final SessionMapper sessionMapper;
    private final ScriptMapper scriptMapper;
    private final ShopMapper shopMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /** RabbitMQ Exchange / RoutingKey —— 与 RabbitMQConfig 定义保持一致 */
    public static final String EXCHANGE_SESSION_CLOSE = "session.close.exchange";
    public static final String ROUTING_KEY_ORDER_CANCEL = "order.cancel";

    // ===================== 创建场次 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createSession(SessionCreateReq req, Long operatorId, String role) {
        // ① 校验剧本、店铺是否存在
        ScriptInfo script = scriptMapper.selectById(req.getScriptId());
        if (script == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "剧本不存在");
        }
        ShopInfo shop = shopMapper.selectById(req.getShopId());
        if (shop == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "店铺不存在");
        }

        // ①.5 店长归属校验：非 ADMIN 只能给"自己拥有的店铺"创建场次
        assertShopOwner(shop, operatorId, role);

        // ② DM 冲突检测（仅当 dmId 不为空时）
        if (req.getDmId() != null) {
            int conflict = sessionMapper.countDmConflict(
                    req.getDmId(), req.getSessionDate(), req.getStartTime(), req.getEndTime());
            if (conflict > 0) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                        "DM 此时段已有场次，冲突数量: " + conflict);
            }
        }

        // ③ 时间合理性校验：endTime > startTime
        if (!req.getEndTime().isAfter(req.getStartTime())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "结束时间必须晚于开始时间");
        }

        // ④ INSERT 场次（status 默认 1，booked 默认 0）
        SessionInfo session = new SessionInfo();
        session.setScriptId(req.getScriptId());
        session.setShopId(req.getShopId());
        session.setDmId(req.getDmId());
        session.setSessionDate(req.getSessionDate());
        session.setStartTime(req.getStartTime());
        session.setEndTime(req.getEndTime());
        session.setCapacity(req.getCapacity());
        session.setBooked(0);
        session.setStatus(1);
        sessionMapper.insert(session);

        // ⑤ 主动写 Redis 库存缓存（项目约定：场次创建时必须写入 session:{id} HASH）
        //    防止新场次 ID 复用了尚未过期的旧缓存 key → 下单按旧 capacity/booked 扣减（数据错乱）
        //    key 结构与 order-service StockService 的 lazyInit 完全一致：HASH {capacity, booked}
        String redisKey = RedisKeyConstant.SESSION_KEY + session.getId();
        try {
            Map<String, String> stockFields = new HashMap<>(4);
            stockFields.put("capacity", String.valueOf(session.getCapacity()));
            stockFields.put("booked", "0");
            stringRedisTemplate.opsForHash().putAll(redisKey, stockFields);

            // 动态 TTL：场次结束时间 + 24h（与 order-service StockService.calculateTtl 约定一致），
            // 最少 60 秒防止立即过期
            long ttlSeconds = ChronoUnit.SECONDS.between(
                    LocalDateTime.now(),
                    LocalDateTime.of(session.getSessionDate(), session.getEndTime())) + 86400L;
            stringRedisTemplate.expire(redisKey, Math.max(ttlSeconds, 60L), TimeUnit.SECONDS);

            log.info("[createSession] 已写入 Redis 库存缓存 key={}, capacity={}, booked=0, ttl={}s",
                    redisKey, session.getCapacity(), ttlSeconds);
        } catch (Exception e) {
            // Redis 不可用不影响场次创建：order-service 侧 decrStock 发现 key 不存在会 lazyInit 自愈
            log.warn("[createSession] 写 Redis 库存缓存失败（不影响场次创建，下单时 lazyInit 会自愈），key={}, reason={}",
                    redisKey, e.getMessage());
        }

        log.info("[createSession] id={}, scriptId={}, shopId={}, dmId={}, date={} {}-{}",
                session.getId(), session.getScriptId(), session.getShopId(),
                session.getDmId(), session.getSessionDate(), session.getStartTime(), session.getEndTime());
        return session.getId();
    }

    // ===================== 关闭场次 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeSession(Long sessionId, Long operatorId, String role) {
        // ① 查场次
        SessionInfo session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "场次不存在");
        }
        if (session.getStatus() == 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "场次已关闭");
        }

        // ①.5 店长归属校验：非 ADMIN 只能关闭"自己店铺"的场次
        ShopInfo shop = shopMapper.selectById(session.getShopId());
        assertShopOwner(shop, operatorId, role);

        // ② 开场前 2 小时内不可关闭
        //    场次开始时刻 = sessionDate + startTime
        LocalDateTime sessionStart = LocalDateTime.of(session.getSessionDate(), session.getStartTime());
        long hoursUntilStart = ChronoUnit.HOURS.between(LocalDateTime.now(), sessionStart);
        if (hoursUntilStart < 2) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "开场前 2 小时内不能关闭场次");
        }

        // ③ UPDATE status → 0
        SessionInfo update = new SessionInfo();
        update.setId(sessionId);
        update.setStatus(0);
        sessionMapper.updateById(update);

        // ④ DELETE Redis session 库存 key（如果存在）
        String redisKey = RedisKeyConstant.SESSION_KEY + sessionId;
        Boolean deleted = stringRedisTemplate.delete(redisKey);
        log.info("[closeSession] Redis key={} 删除结果={}", redisKey, deleted);

        // ⑤ 统计该场次未支付订单的玩家总数
        int pendingPlayerCnt = sessionMapper.sumPendingPlayerCnt(sessionId);
        log.info("[closeSession] 未支付玩家总数={}", pendingPlayerCnt);

        // ⑥ 发 RabbitMQ 消息（立即执行，不延迟）
        Map<String, Object> payload = new HashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("shopId", session.getShopId());
        payload.put("playerCntTotal", pendingPlayerCnt);
        payload.put("cancelReason", "SESSION_CLOSED");
        payload.put("operator", operatorId);

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("序列化关闭场次 MQ 消息失败", e);
        }
        rabbitTemplate.convertAndSend(EXCHANGE_SESSION_CLOSE, ROUTING_KEY_ORDER_CANCEL, json);
        log.info("[closeSession] 已发送 MQ 消息 exchange={}, routingKey={}, payload={}",
                EXCHANGE_SESSION_CLOSE, ROUTING_KEY_ORDER_CANCEL, json);
    }

    /**
     * 店长归属校验公共逻辑：店长只能操作自己名下店铺（owner_id 匹配）
     *
     * <p>修复越权：原 createSession/closeSession 不校验操作人与店铺的从属关系，
     * 任何店长都能操作任意店铺的场次。此处强制归属，防横向越权。
     */
    private void assertShopOwner(ShopInfo shop, Long operatorId, String role) {
        if (operatorId == null || shop == null || shop.getOwnerId() == null
                || !shop.getOwnerId().equals(operatorId)) {
            log.warn("[SessionServiceImpl] 店长越权拦截 operatorId={}, role={}, shopId={}, ownerId={}",
                    operatorId, role, shop != null ? shop.getId() : null,
                    shop != null ? shop.getOwnerId() : null);
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "只能操作自己店铺的场次");
        }
    }

    // ===================== 查询：未来 7 天有效场次 =====================

    @Override
    public List<SessionRes> listSessionsByScript(Long scriptId) {
        QueryWrapper<SessionInfo> w = new QueryWrapper<>();
        w.eq("script_id", scriptId)
                .eq("status", 1)
                .ge("session_date", LocalDate.now())
                .orderByAsc("session_date")
                .orderByAsc("start_time");
        return sessionMapper.selectList(w).stream()
                .map(SessionRes::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<SessionRes> listSessionsByShop(Long shopId) {
        QueryWrapper<SessionInfo> w = new QueryWrapper<>();
        w.eq("shop_id", shopId)
                .eq("status", 1)
                .ge("session_date", LocalDate.now())
                .orderByAsc("session_date")
                .orderByAsc("start_time");
        return sessionMapper.selectList(w).stream()
                .map(SessionRes::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public SessionRes getSessionDetail(Long sessionId) {
        SessionInfo s = sessionMapper.selectById(sessionId);
        if (s == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "场次不存在");
        }
        return SessionRes.fromEntity(s);
    }

    @Override
    public SessionFeignRes getSessionForFeign(Long sessionId) {
        SessionInfo s = sessionMapper.selectById(sessionId);
        if (s == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "场次不存在");
        }

        // 附带剧本单价：order-service 下单时据此计算支付金额（amount = price × playerCnt）
        // 剧本不存在（数据异常）时 price 传 null，由 order-service 侧兜底为 0
        BigDecimal price = null;
        ScriptInfo script = scriptMapper.selectById(s.getScriptId());
        if (script != null) {
            price = script.getPrice();
        }

        return SessionFeignRes.builder()
                .id(s.getId())
                .scriptId(s.getScriptId())
                .shopId(s.getShopId())
                .dmId(s.getDmId())
                .sessionDate(s.getSessionDate())
                .startTime(s.getStartTime())
                .endTime(s.getEndTime())
                .capacity(s.getCapacity())
                .booked(s.getBooked() == null ? 0 : s.getBooked())
                .price(price)
                .status(s.getStatus())
                .build();
    }
}
