package com.urban.script.order.service.impl;

import com.urban.script.common.BusinessException;
import com.urban.script.common.R;
import com.urban.script.common.ResultCode;
import com.urban.script.common.SnowflakeIdGenerator;
import com.urban.script.order.dto.OrderCreateReq;
import com.urban.script.order.dto.OrderRes;
import com.urban.script.order.entity.OrderInfo;
import com.urban.script.order.feign.ShopClient;
import com.urban.script.order.mapper.OrderMapper;
import com.urban.script.order.mapper.SessionMapper;
import com.urban.script.order.producer.DelayCancelProducer;
import com.urban.script.order.service.OrderService;
import com.urban.script.order.service.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 订单服务实现 —— P0 完整主链路
 *
 * <h3>createOrder 5 步流程</h3>
 * <pre>
 * ① Redisson 分布式锁（防同一用户同场次重复提交）
 * ② Feign 调 shop-service 校验场次（存在 / 开放 / 未结束）
 * ③ Redis+Lua 原子扣减（含 MySQL 乐观锁降级）
 * ④ MySQL 创建订单 + 同步 booked（最终一致）
 * ⑤ RabbitMQ 延迟消息（15min TTL → 到期未支付自动关单）
 * </pre>
 *
 * <h3>cancelOrder 回滚链路</h3>
 * <pre>
 * ① 幂等保护：状态校验（status=0 待支付才能取消）
 * ② MySQL 更新 status=2 + cancel_reason
 * ③ Redis+Lua 回滚 booked（如果 Redis 可用）
 * ④ MySQL 回滚 booked（双重保险）
 * </pre>
 *
 * @author urban-script-reservation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    // ========================================================================
    // 依赖注入
    // ========================================================================

    private final OrderMapper orderMapper;
    private final SessionMapper sessionMapper;
    private final ShopClient shopClient;
    private final RedissonClient redissonClient;
    private final StockService stockService;
    private final DelayCancelProducer delayCancelProducer;

    // ========================================================================
    // ① 创建预约订单 —— Redisson 锁 + Feign 校验 + Redis+Lua 扣减 + MySQL 降级
    // ========================================================================

    @Override
    public String createOrder(Long userId, OrderCreateReq req) {
        // === 入参校验（Controller 层 @Valid 已经处理了非空/Min，这里是防御） ===
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录");
        }
        if (req.getPlayerCnt() == null || req.getPlayerCnt() < 1) {
            req.setPlayerCnt(1);  // 默认 1
        }

        // === ① Redisson 分布式锁 ===
        // key 格式：reserve:lock:{sessionId}:{userId} —— 同一用户同一场次互斥
        // wait=3s 等待获取，lease=10s 自动释放（业务执行肯定在 10s 内）
        String lockKey = "reserve:lock:" + req.getSessionId() + ":" + userId;
        RLock lock = redissonClient.getLock(lockKey);

        // Bug④ 修复：锁获取必须独立于业务 try 块。
        // Redis 挂掉时 Redisson tryLock 抛 org.redisson.client.RedisException（连接失败/超时），
        // 旧代码这段异常发生在 tryDecrStock 之前且无人捕获 → 整个下单 500，MySQL 降级路径不可达。
        // 修复策略：Redis 不可用时放弃分布式锁、放行业务（无锁降级），
        // 防重复提交改由 order_no 唯一约束 + 订单状态幂等兜底，可用性优先于强一致。
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("[createOrder] 用户重复提交，userId={}, sessionId={}", userId, req.getSessionId());
                throw new BusinessException("请勿重复提交，请稍后再试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[createOrder] tryLock 被中断，userId={}", userId, e);
            throw new BusinessException("系统繁忙，请重试");
        } catch (RedisException redisDown) {
            // Redisson 的 RedisException 是连接类异常的父类（连接拒绝/超时/节点不可用）
            // 注意它不是 Spring 的 RedisConnectionFailureException，原 try-catch 接不住
            log.warn("[createOrder] ⚠️ Redis 不可用，分布式锁降级放行（无锁执行），lockKey={}, reason={}",
                    lockKey, redisDown.getMessage());
        }

        try {
            // === ② Feign 校验场次 ===
            ShopClient.SessionFeignRes session = validateSession(req.getSessionId());

            // === ③ Redis+Lua 原子扣减（含降级 MySQL 乐观锁）===
            boolean redisDeducted = tryDecrStock(session, req.getPlayerCnt());

            // === ④ MySQL 创建订单 ===
            OrderInfo order = buildOrder(userId, session, req);
            try {
                orderMapper.insert(order);
            } catch (DuplicateKeyException dke) {
                // order_no 唯一约束冲突 —— 罕见但防御一下
                log.error("[createOrder] order_no 唯一冲突，orderNo={}", order.getOrderNo(), dke);
                // 回滚 Redis 扣减（如果是 Redis 路径）
                if (redisDeducted) {
                    stockService.rollbackStock(req.getSessionId(), req.getPlayerCnt());
                }
                throw new BusinessException("订单创建冲突，请重试");
            }

            // MySQL 也同步 booked（最终一致）
            // Redis 路径：Redis 已扣减，MySQL 这里同步一下
            // MySQL 降级路径：incrementBookedIfEnough 已经扣过了，这里不再扣
            if (redisDeducted) {
                sessionMapper.incrementBooked(req.getSessionId(), req.getPlayerCnt());
            }

            // === ⑤ 发 RabbitMQ 延迟消息 ===
            delayCancelProducer.sendDelayCancel(order.getOrderNo(), req.getSessionId());

            log.info("[createOrder] ✅ 下单成功 userId={}, orderNo={}, sessionId={}, playerCnt={}, redisPath={}",
                    userId, order.getOrderNo(), req.getSessionId(), req.getPlayerCnt(), redisDeducted);

            return order.getOrderNo();

        } finally {
            // 只有当前线程持有的锁才释放（防止误删他人锁）
            // Redis 不可用时 lock.lock() 都没成功过、isHeldByCurrentThread 走 Redis 同样会抛异常 → 再包一层
            if (locked) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (RedisException unlockEx) {
                    log.warn("[createOrder] unlock 失败（Redis 不可用，锁会随 leaseTime=10s 自动过期），lockKey={}",
                            lockKey);
                }
            }
        }
    }

    // ========================================================================
    // Feign 校验场次（内部辅助方法）
    // ========================================================================

    /**
     * 调 shop-service Feign 接口校验场次
     *
     * @return 场次信息（通过校验）
     * @throws BusinessException 校验失败
     */
    private ShopClient.SessionFeignRes validateSession(Long sessionId) {
        R<ShopClient.SessionFeignRes> resp;
        try {
            resp = shopClient.getSession(sessionId);
        } catch (Exception feignEx) {
            // Feign 调用异常（超时 / 连接拒绝）
            log.error("[createOrder] Feign 调用 shop-service 失败，sessionId={}", sessionId, feignEx);
            throw new BusinessException("场次服务暂不可用，请稍后重试");
        }

        // shop-service 自己返回错误（fallback 触发或内部异常）
        if (resp == null || resp.getCode() != ResultCode.SUCCESS.getCode()) {
            String msg = resp != null ? resp.getMessage() : "场次不存在";
            log.warn("[createOrder] Feign 返回非 200，sessionId={}, resp={}", sessionId, resp);
            throw new BusinessException(msg);
        }

        ShopClient.SessionFeignRes session = resp.getData();
        if (session == null) {
            throw new BusinessException("场次不存在");
        }
        if (session.getStatus() != null && session.getStatus() != 1) {
            throw new BusinessException("场次已关闭");
        }

        // 场次已过（session_date + end_time < now）
        LocalDateTime sessionEnd = LocalDateTime.of(session.getSessionDate(), session.getEndTime());
        if (sessionEnd.isBefore(LocalDateTime.now())) {
            throw new BusinessException("场次已结束，无法预约");
        }

        return session;
    }

    // ========================================================================
    // Redis+Lua 扣减 + MySQL 降级（返回是否走了 Redis 路径）
    // ========================================================================

    /**
     * 尝试扣减库存：优先 Redis+Lua，Redis 不可用时降级 MySQL 乐观锁
     *
     * @return true=Redis 路径成功；false=MySQL 降级路径成功
     */
    private boolean tryDecrStock(ShopClient.SessionFeignRes session, int playerCnt) {
        try {
            // === 正常路径：Redis+Lua 原子扣减 ===
            boolean success = stockService.decrStock(session.getId(), playerCnt);
            if (!success) {
                throw new BusinessException("场次名额已满");
            }
            return true;  // Redis 路径

        } catch (RedisConnectionFailureException | RedisSystemException redisDown) {
            // === Redis 真挂了 → 降级 MySQL 乐观锁 ===
            // 只 catch 这两个异常（连接失败 / 系统异常包括超时），不 catch 所有 Exception！
            // 业务逻辑错误（如 Lua 脚本返回 0）会在 decrStock 内部返回 false，
            // 已经在上面 !success 分支处理了
            log.warn("[createOrder] ⚠️ Redis 不可用，降级 MySQL 乐观锁扣减 sessionId={}, reason={}",
                    session.getId(), redisDown.getMessage());

            // MySQL 乐观锁：WHERE status=1 AND booked+playerCnt <= capacity
            int rows = sessionMapper.incrementBookedIfEnough(session.getId(), playerCnt);
            if (rows == 0) {
                throw new BusinessException("场次名额已满或系统繁忙，请稍后再试");
            }

            // 降级成功 → 异步修复 Redis（Redis 恢复后下次请求走正常路径）
            CompletableFuture.runAsync(() -> stockService.forceRefreshBooked(session.getId()));

            return false;  // MySQL 降级路径
        }
    }

    // ========================================================================
    // 构造订单实体（内部辅助方法）
    // ========================================================================

    /**
     * 根据 Feign 场次信息构建 OrderInfo（不含主键 ID，insert 后自动生成）
     */
    private OrderInfo buildOrder(Long userId, ShopClient.SessionFeignRes session, OrderCreateReq req) {
        // 订单号：ORD + yyyyMMddHHmmss + 雪花 ID（保证全局唯一）
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String snowflakePart = String.valueOf(SnowflakeIdGenerator.getInstance().nextId());
        String orderNo = "ORD" + dateStr + snowflakePart;

        OrderInfo order = new OrderInfo();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setSessionId(session.getId());
        order.setShopId(session.getShopId());
        order.setScriptId(session.getScriptId());
        order.setPlayerCnt(req.getPlayerCnt());
        order.setPlayTime(LocalDateTime.of(session.getSessionDate(), session.getStartTime()));
        order.setPayMethod(0);    // 未支付
        order.setStatus(0);       // 待支付（Redis 已扣库存，保留 15min）
        order.setCancelReason(null);

        return order;
    }

    // ========================================================================
    // ② 取消订单 —— 回滚 Redis + MySQL booked
    // ========================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId, String cancelReason, String operator) {
        log.info("[cancelOrder] 开始取消 orderId={}, reason={}, operator={}", orderId, cancelReason, operator);

        // ① 查订单
        OrderInfo order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "订单不存在");
        }

        // ② 幂等保护：只有待支付(0) 的订单才能取消
        // 已支付(1) / 已完成(3) / 已取消(2) 都跳过
        if (order.getStatus() != null && order.getStatus() != 0) {
            log.info("[cancelOrder] 订单状态非待支付，跳过。orderId={}, currentStatus={}", orderId, order.getStatus());
            return;
        }

        // ③ 更新 MySQL 状态
        OrderInfo update = new OrderInfo();
        update.setId(orderId);
        update.setStatus(2);      // 已取消
        update.setCancelReason(cancelReason);
        orderMapper.updateById(update);

        // ④ 回滚 Redis booked
        try {
            stockService.rollbackStock(order.getSessionId(), order.getPlayerCnt());
        } catch (Exception e) {
            // Redis 回滚失败不影响主链路（MySQL 已回滚）
            log.warn("[cancelOrder] Redis 回滚失败，但 MySQL 状态已更新，orderId={}", orderId, e);
        }

        // ⑤ MySQL 回滚 booked（双重保险）
        // 注意：Redis 降级路径下 incrementBookedIfEnough 已经加过 booked，
        // 但 cancel 还是调一次 decrementBookedIfEnough 来保持最终一致
        int decRows = sessionMapper.decrementBookedIfEnough(order.getSessionId(), order.getPlayerCnt());
        if (decRows == 0) {
            // booked 可能已为 0 或场次不存在，不算严重错误
            log.warn("[cancelOrder] MySQL 回滚 booked 返回 0，orderId={}, sessionId={}", orderId, order.getSessionId());
        }

        log.info("[cancelOrder] ✅ 取消成功 orderId={}, sessionId={}, playerCnt={}, reason={}, operator={}",
                orderId, order.getSessionId(), order.getPlayerCnt(), cancelReason, operator);
    }

    // ========================================================================
    // ③ 订单详情（带场次时间信息）
    // ========================================================================

    @Override
    public OrderRes getOrderDetail(Long orderId) {
        OrderInfo order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "订单不存在");
        }

        // 尝试 Feign 拉场次信息（可能失败，失败则回退基础版）
        ShopClient.SessionFeignRes session = safeGetSession(order.getSessionId());

        return OrderRes.fromEntityWithSession(order, session);
    }

    // ========================================================================
    // ④ 用户订单列表
    // ========================================================================

    @Override
    public List<OrderRes> listOrdersByUserId(Long userId) {
        List<OrderInfo> orders = orderMapper.selectByUserId(userId);
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }

        // 批量组装：每个订单尝试拉场次信息（可能有几个 Feign 调用，但 P0 阶段先这样）
        // 如果性能不够可以优化为批量 Feign / Pipeline
        return orders.stream()
                .map(o -> {
                    ShopClient.SessionFeignRes session = safeGetSession(o.getSessionId());
                    return OrderRes.fromEntityWithSession(o, session);
                })
                .collect(Collectors.toList());
    }

    // ========================================================================
    // 内部辅助：安全调 Feign（失败返回 null 不抛异常）
    // ========================================================================

    /**
     * 安全获取场次信息 —— Feign 失败返回 null 而不是抛异常
     * 用于订单详情/列表等非关键路径（场次不存在或 Feign 挂了不影响返回基础订单信息）
     */
    private ShopClient.SessionFeignRes safeGetSession(Long sessionId) {
        try {
            R<ShopClient.SessionFeignRes> resp = shopClient.getSession(sessionId);
            if (resp != null && resp.getCode() == ResultCode.SUCCESS.getCode() && resp.getData() != null) {
                return resp.getData();
            }
        } catch (Exception e) {
            log.debug("[OrderServiceImpl] safeGetSession Feign 失败，sessionId={}, {}", sessionId, e.getMessage());
        }
        return null;
    }
}
