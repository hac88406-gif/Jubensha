package com.urban.script.order.service;

import com.urban.script.order.entity.SessionInfo;
import com.urban.script.order.mapper.SessionMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 场次库存服务 —— Redis+Lua 原子扣减（独立基础设施层）
 *
 * <p>5 个优化点全在这里：
 * <ol>
 *   <li><b>Lua 原子性</b> —— 扣减 / 回滚全走 Lua 脚本，避免 check-then-act 竞态</li>
 *   <li><b>静态预加载 SHA1</b> —— {@code static} 块读 Lua 文件 → SCRIPT LOAD 到 Redis → 拿到 SHA1，
 *       后续只发 SHA1 不发脚本内容（~10KB 节省 → 每次请求只传 40 字节 SHA1）</li>
 *   <li><b>懒加载 + 自动重试</b> —— decrStock 发现 Redis key 不存在（Lua 返回 -1）→ 从 MySQL 查 capacity+booked
 *       写入 Redis HASH → while(true) 重试扣减（最多一次，不会无限循环）</li>
 *   <li><b>HASH 结构</b> —— 一个 session:{id} HASH 存 capacity + booked 两个字段，
 *       比两个 STRING key 省内存 + 原子性更好（HINCRBY 原子自增 + 同时读两个字段判断）</li>
 *   <li><b>动态 TTL + 分布式锁懒加载</b> —— TTL = 场次结束时间 + 24h（过期自动清理）；
 *       用 Redisson RLock 防止并发下多线程同时懒加载覆盖同一 key</li>
 * </ol>
 *
 * <p>Redis key 空间约定：
 * <pre>
 *   session:{sessionId}    HASH    { capacity: "6", booked: "3" }    TTL = 场次结束后24h
 *   stock:init:{sessionId} STRING  Redisson RLock 互斥锁，防止并发懒加载
 * </pre>
 *
 * <p>Template 约束：<b>所有库存 Redis 操作统一走 {@code StringRedisTemplate}</b>，
 * 绝不用 {@code RedisTemplate}（JSON 序列化会让 Lua 的 tonumber() 拿到字符串"6"而非数字 6，判断恒失败）。
 *
 * @author urban-script-reservation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    // ========================================================================
    // Lua SHA1 预加载（static 块 + @PostConstruct 双保险）
    // ========================================================================

    /** reserve_stock.lua 的 SHA1（SCRIPT LOAD 后拿到） */
    private static String LUA_DECR_SHA;
    /** reserve_rollback.lua 的 SHA1 */
    private static String LUA_ROLLBACK_SHA;

    static {
        // 先读文件内容，SHA1 在 @PostConstruct 里 SCRIPT LOAD 到 Redis 后赋值
        try {
            ClassPathResource decrRes = new ClassPathResource("lua/reserve_stock.lua");
            ClassPathResource rollbackRes = new ClassPathResource("lua/reserve_rollback.lua");

            LUA_DECR_SCRIPT = new String(decrRes.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            LUA_ROLLBACK_SCRIPT = new String(rollbackRes.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Lua 脚本加载失败", e);
        }
    }

    private static String LUA_DECR_SCRIPT;
    private static String LUA_ROLLBACK_SCRIPT;

    // ========================================================================
    // 依赖注入
    // ========================================================================

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final SessionMapper sessionMapper;

    // ========================================================================
    // @PostConstruct —— SCRIPT LOAD 预编译 Lua → 拿 SHA1
    // ========================================================================

    @PostConstruct
    public void preloadLuaScripts() {
        log.info("[StockService] 开始预加载 Lua 脚本到 Redis ...");
        try {
            LUA_DECR_SHA = stringRedisTemplate.execute((RedisCallback<String>) conn ->
                    conn.scriptLoad(LUA_DECR_SCRIPT.getBytes(StandardCharsets.UTF_8))
            );
            LUA_ROLLBACK_SHA = stringRedisTemplate.execute((RedisCallback<String>) conn ->
                    conn.scriptLoad(LUA_ROLLBACK_SCRIPT.getBytes(StandardCharsets.UTF_8))
            );
            log.info("[StockService] Lua 预加载完成 —— decr.sha={}, rollback.sha={}",
                    LUA_DECR_SHA, LUA_ROLLBACK_SHA);
        } catch (Exception e) {
            // 启动时 Redis 挂了也不让 Bean 创建失败。
            // EVALSHA 找不到 SHA1 会抛 NOSCRIPT，evalDecr/evalRollback 里已经有重 LOAD 的自愈逻辑，
            // 第一次扣减请求进 evalDecr 时会自动 SCRIPT LOAD 补救。
            log.warn("[StockService] Lua 脚本预加载失败（Redis 可能不可用），将在首次扣减时自愈: {}",
                    e.getMessage());
        }
    }

    // ========================================================================
    // 主入口：扣减库存（含懒加载 + 自动重试一次）
    // ========================================================================

    /**
     * 原子扣减场次库存（含懒加载）
     *
     * @param sessionId 场次 ID
     * @param playerCnt 要扣减的玩家数（正整数）
     * @return true=扣减成功（booked 已原子 +playerCnt）；false=名额不足
     * @throws IllegalArgumentException playerCnt ≤ 0
     */
    public boolean decrStock(Long sessionId, int playerCnt) {
        if (sessionId == null || playerCnt <= 0) {
            throw new IllegalArgumentException("sessionId 和 playerCnt 必须为正整数");
        }

        int retry = 0;
        while (retry < 2) {  // 最多 1 次懒加载 + 1 次正常扣减
            Long result = evalDecr(sessionId, playerCnt);

            // null 等价于 Redis 返回的 integer 类型（Redis 6+ script 返回 integer）
            if (result == null || result == -1L) {
                // key 不存在 → 懒加载后重试
                retry++;
                log.info("[StockService] session:{} Redis key 不存在，触发懒加载 (retry={})",
                        sessionId, retry);
                if (!lazyInit(sessionId)) {
                    // MySQL 也查不到（场次不存在或已关闭），直接失败
                    log.warn("[StockService] session:{} 懒加载失败（场次不存在或已关闭）", sessionId);
                    return false;
                }
                continue;
            }

            // Lua 返回 1=成功 0=名额不足
            return result == 1L;
        }

        // retry 耗尽（正常不会走到这里）
        log.error("[StockService] session:{} decrStock retry 耗尽，playerCnt={}", sessionId, playerCnt);
        return false;
    }

    // ========================================================================
    // Lua 执行层（纯 SHA1 evalSha，不发脚本内容）
    // ========================================================================

    /**
     * 执行扣减 Lua 脚本（evalSha，纯 SHA1 调用）
     *
     * <p> Bug⑤ 修复：Redis 重启 / SCRIPT FLUSH 后脚本缓存清空，EVALSHA 会返回 NOSCRIPT 错误，
     * 而静态字段仍持有旧 SHA1 → 每次扣减都抛异常，必须重启服务才能恢复（运维级故障）。
     * 这里捕获 NOSCRIPT 后重新 SCRIPT LOAD 再重试一次，实现运行时自愈。
     *
     * @return 1=成功 0=名额不足 -1=key 不存在（让 Java 层懒加载）
     */
    private Long evalDecr(Long sessionId, int playerCnt) {
        try {
            return doEvalDecrSha(LUA_DECR_SHA, sessionId, playerCnt);
        } catch (RedisSystemException e) {
            if (!isNoScript(e)) {
                throw e;  // 其他 Redis 异常原样上抛（让上层 MySQL 降级逻辑接管）
            }
            log.warn("[StockService] EVALSHA NOSCRIPT（Redis 重启/SCRIPT FLUSH 导致脚本缓存丢失），"
                    + "重新 SCRIPT LOAD 扣减脚本, sessionId={}", sessionId);
            LUA_DECR_SHA = reloadScript(LUA_DECR_SCRIPT);
            return doEvalDecrSha(LUA_DECR_SHA, sessionId, playerCnt);
        }
    }

    /** evalSha 扣减脚本的实际执行体（不做异常翻译，给 evalDecr 做重试用） */
    private Long doEvalDecrSha(String sha, Long sessionId, int playerCnt) {
        return stringRedisTemplate.execute((RedisCallback<Long>) connection ->
                connection.evalSha(
                        sha,
                        ReturnType.INTEGER,
                        1,
                        key(sessionId).getBytes(StandardCharsets.UTF_8),
                        String.valueOf(playerCnt).getBytes(StandardCharsets.UTF_8)
                )
        );
    }

    /**
     * 执行回滚 Lua 脚本（同样带 NOSCRIPT 兜底，理由同 evalDecr）
     */
    private Long evalRollback(Long sessionId, int playerCnt) {
        try {
            return doEvalRollbackSha(LUA_ROLLBACK_SHA, sessionId, playerCnt);
        } catch (RedisSystemException e) {
            if (!isNoScript(e)) {
                throw e;
            }
            log.warn("[StockService] EVALSHA NOSCRIPT（Redis 重启/SCRIPT FLUSH 导致脚本缓存丢失），"
                    + "重新 SCRIPT LOAD 回滚脚本, sessionId={}", sessionId);
            LUA_ROLLBACK_SHA = reloadScript(LUA_ROLLBACK_SCRIPT);
            return doEvalRollbackSha(LUA_ROLLBACK_SHA, sessionId, playerCnt);
        }
    }

    /** evalSha 回滚脚本的实际执行体 */
    private Long doEvalRollbackSha(String sha, Long sessionId, int playerCnt) {
        return stringRedisTemplate.execute((RedisCallback<Long>) connection ->
                connection.evalSha(
                        sha,
                        ReturnType.INTEGER,
                        1,
                        key(sessionId).getBytes(StandardCharsets.UTF_8),
                        String.valueOf(playerCnt).getBytes(StandardCharsets.UTF_8)
                )
        );
    }

    /**
     * 判断异常链里是否为 NOSCRIPT（EVALSHA 找不到脚本时 Redis 返回的标准错误）。
     * Lettuce 会把它包成 RedisCommandExecutionException，再被 Spring 包成 RedisSystemException，
     * 所以要沿 cause 链逐层找关键字。
     */
    private boolean isNoScript(RedisSystemException e) {
        Throwable cur = e;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains("NOSCRIPT")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 重新 SCRIPT LOAD 脚本到 Redis 并返回新 SHA1（NOSCRIPT 自愈用）。
     * 并发场景下多个线程同时 reload 是幂等的（同一脚本内容 LOAD 得到同一 SHA1），无需加锁。
     */
    private String reloadScript(String script) {
        return stringRedisTemplate.execute((RedisCallback<String>) conn ->
                conn.scriptLoad(script.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static String key(Long sessionId) {
        return "session:" + sessionId;
    }

    // ========================================================================
    // 回滚库存（取消订单时调）
    // ========================================================================

    /**
     * 原子回滚场次库存
     *
     * @param sessionId 场次 ID
     * @param playerCnt 要加回的玩家数（正整数）
     */
    public void rollbackStock(Long sessionId, int playerCnt) {
        if (sessionId == null || playerCnt <= 0) return;

        Long result = evalRollback(sessionId, playerCnt);
        // 0 = key 不存在（可能过期/场次关闭），不算错
        log.info("[StockService] rollbackStock session:{}, playerCnt={}, result={}",
                sessionId, playerCnt, result);
    }

    // ========================================================================
    // Pipeline 批量查库存
    // ========================================================================

    /**
     * Pipeline 批量查询多场次库存
     *
     * @param sessionIds 场次 ID 列表
     * @return Map<sessionId, Map<"capacity"|"booked", String>> —— 只包含 Redis 中存在的场次
     */
    public Map<Long, Map<String, String>> batchGetStock(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) return Map.of();

        List<Object> rawResults = stringRedisTemplate.executePipelined(
                (RedisCallback<Object>) connection -> {
                    for (Long id : sessionIds) {
                        connection.hashCommands().hGetAll(key(id).getBytes(StandardCharsets.UTF_8));
                    }
                    return null;
                }
        );

        Map<Long, Map<String, String>> result = new HashMap<>();
        for (int i = 0; i < sessionIds.size(); i++) {
            Long sessionId = sessionIds.get(i);
            @SuppressWarnings("unchecked")
            Map<byte[], byte[]> raw = (Map<byte[], byte[]>) rawResults.get(i);
            if (raw != null && !raw.isEmpty()) {
                Map<String, String> decoded = new HashMap<>(raw.size());
                raw.forEach((k, v) -> decoded.put(new String(k, StandardCharsets.UTF_8),
                        new String(v, StandardCharsets.UTF_8)));
                result.put(sessionId, decoded);
            }
        }
        log.debug("[StockService] batchGetStock hit {}/{}", result.size(), sessionIds.size());
        return result;
    }

    // ========================================================================
    // 懒加载：MySQL → Redis HASH（Redisson RLock 防并发）
    // ========================================================================

    /**
     * 从 MySQL 查 session_info 写 Redis HASH
     *
     * @return true=成功写入；false=场次不存在或已关闭
     */
    private boolean lazyInit(Long sessionId) {
        // Redisson 分布式锁：stock:init:{sessionId} 防止多线程同时懒加载
        String lockKey = "stock:init:" + sessionId;
        RLock lock = redissonClient.getLock(lockKey);

        boolean locked;
        try {
            locked = lock.tryLock(1, 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[StockService] lazyInit 获取锁被中断, sessionId={}", sessionId);
            return false;
        }

        if (!locked) {
            // 别的线程正在懒加载，短暂等待后让 decrStock 再重试一次（最多到外层 retry 耗尽）
            log.info("[StockService] lazyInit 锁竞争, sessionId={}, 跳过本轮让外层重试", sessionId);
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            return true;  // 返回 true 让外层 while 再 evalDecr 一次（此时大概率锁持有者已写完）
        }

        try {
            // double-check：拿到锁再查一次 Redis，可能锁持有者已写完
            String exists = stringRedisTemplate.hasKey(key(sessionId)) ? "Y" : "N";
            if ("Y".equals(exists)) {
                log.debug("[StockService] lazyInit double-check 发现 key 已存在, sessionId={}", sessionId);
                return true;
            }

            // 1. 查 MySQL
            SessionInfo session = sessionMapper.selectById(sessionId);
            if (session == null) {
                log.warn("[StockService] lazyInit sessionId={} 不存在", sessionId);
                return false;
            }
            if (session.getStatus() != null && session.getStatus() == 0) {
                log.warn("[StockService] lazyInit sessionId={} 已关闭，不加载", sessionId);
                return false;
            }

            // 2. 写 Redis HASH
            Map<String, String> fields = new HashMap<>(4);
            fields.put("capacity", String.valueOf(session.getCapacity()));
            fields.put("booked", String.valueOf(session.getBooked()));
            stringRedisTemplate.opsForHash().putAll(key(sessionId), fields);

            // 3. 动态 TTL：场次结束时间 + 24h
            long ttlSeconds = calculateTtl(session.getSessionDate(), session.getEndTime());
            if (ttlSeconds > 0) {
                stringRedisTemplate.expire(key(sessionId), ttlSeconds, TimeUnit.SECONDS);
                log.info("[StockService] lazyInit 完成, sessionId={}, capacity={}, booked={}, ttl={}s",
                        sessionId, session.getCapacity(), session.getBooked(), ttlSeconds);
            } else {
                // 场次已过期，不应该加载（防御）
                log.warn("[StockService] lazyInit sessionId={} TTL<=0 (已过期), 跳过", sessionId);
                stringRedisTemplate.delete(key(sessionId));
                return false;
            }

            return true;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 计算动态 TTL：场次结束时间 - now + 24h
     */
    private long calculateTtl(LocalDate date, LocalTime endTime) {
        LocalDateTime sessionEnd = LocalDateTime.of(date, endTime);
        long seconds = ChronoUnit.SECONDS.between(LocalDateTime.now(), sessionEnd) + 86400L;
        return Math.max(seconds, 60L);  // 最少 60 秒防止立即过期
    }

    // ========================================================================
    // 工具：查看当前 Redis 中的库存状态（Debug / 运维用）
    // ========================================================================

    /**
     * 查看 Redis 中某场次的实时库存（Debug 接口）
     */
    public Map<String, String> peekStock(Long sessionId) {
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(key(sessionId));
        Map<String, String> decoded = new HashMap<>(raw.size());
        raw.forEach((k, v) -> decoded.put(k.toString(), v.toString()));
        return decoded;
    }

    /**
     * 手动预热（管理端调用，把 MySQL 库存灌进 Redis）
     *
     * @return true=成功
     */
    public boolean preloadStock(Long sessionId) {
        // 先删旧的（如果存在）让 lazyInit 重新写
        stringRedisTemplate.delete(key(sessionId));
        return lazyInit(sessionId);
    }

    /**
     * 手动清空缓存（管理端调用，场次关闭时触发）
     */
    public void invalidateStock(Long sessionId) {
        stringRedisTemplate.delete(key(sessionId));
        log.info("[StockService] invalidateStock session:{} 已清理", sessionId);
    }

    // ========================================================================
    // Redis → MySQL 降级成功后的异步修复
    // ========================================================================

    /**
     * 强制从 MySQL 重新拉取 booked 覆盖 Redis（降级恢复用）
     *
     * <p>触发场景：Redis 不可用时走 MySQL 降级扣减成功 → 异步调此方法
     * 把 MySQL 当前最新的 booked 数灌回 Redis HASH，让后续请求走正常 Redis 路径。
     *
     * <p>操作：先删旧 key（如果存在）→ 重新 lazyInit 写 MySQL 最新数据 → 设动态 TTL。
     * 幂等——多次调用结果一致。
     *
     * @param sessionId 场次 ID
     */
    public void forceRefreshBooked(Long sessionId) {
        if (sessionId == null) return;
        try {
            // 1. 删旧 key（让 lazyInit 重新写完整数据）
            stringRedisTemplate.delete(key(sessionId));
            // 2. 重新懒加载（会从 MySQL 查最新 capacity + booked 写 Redis）
            lazyInit(sessionId);
            log.info("[StockService] forceRefreshBooked session:{} Redis 库存已从 MySQL 同步修复", sessionId);
        } catch (Exception e) {
            // 异步修复不阻塞主链路，失败只 warn
            log.warn("[StockService] forceRefreshBooked session:{} 修复失败（稍后 Redis 恢复会被下次请求的 lazyInit 覆盖）",
                    sessionId, e);
        }
    }
}
