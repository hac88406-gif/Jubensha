package com.urban.script.order.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 抢位防刷限流服务（业务层，方案选型说明）
 *
 * <p><b>为什么不用网关 RequestRateLimiter</b>：官方限流基于 WebFlux reactive-redis，
 * Redis 不可用时网关会直接 500/406，与项目"Redis 宕机不拖垮主链路、优雅降级 MySQL"的
 * 叙事冲突；且 2000 并发压测必然被网关层截断，压不出真实下单并发数。
 *
 * <p><b>本方案</b>：order-service 业务内做 Redis 计数器限流——
 * <ul>
 *   <li>原子性：INCR + 首次设置 EXPIRE（Redis 的 INCR 自带原子性，无并发超卖窗口）；</li>
 *   <li>按 userId 维度天然分散，演示压测可动态调高 {@code limit} 而不影响架构；</li>
 *   <li>降级策略：Redis 异常时<b>放行</b>（限流器失效不阻塞下单，主链路仍有
 *       Redisson 锁 + Lua 扣减兜底）；</li>
 *   <li>依赖零新增：复用已有 StringRedisTemplate。</li>
 * </ul>
 *
 * @author urban-script-reservation
 */
@Slf4j
@Service
public class RateLimitService {

    /** 计数器 key 前缀：rl:order:{bizKey}，便于线上按前缀批量清理/观测 */
    private static final String KEY_PREFIX = "rl:order:";

    private final StringRedisTemplate stringRedisTemplate;

    public RateLimitService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 尝试获取一次配额（滑动窗口近似：固定窗口计数器）
     *
     * @param bizKey    业务维度（如 "order:{userId}"），窗口内独立计数
     * @param limit     窗口内最大放行次数（大于该值拒绝）
     * @param windowSec 窗口秒数
     * @return true=放行；false=超过限流阈值，调用方应拒绝本次请求
     */
    public boolean tryAcquire(String bizKey, int limit, int windowSec) {
        String fullKey = KEY_PREFIX + bizKey;
        try {
            Long count = stringRedisTemplate.opsForValue().increment(fullKey);
            // INCR 结果为 1 说明是新窗口起点，必须设置过期时间，否则 key 永不过期
            // （漏掉这一步 = Redis 内存泄漏 + 计数器无限累加，最终全部请求被限流）
            if (count != null && count == 1L) {
                stringRedisTemplate.expire(fullKey, Duration.ofSeconds(windowSec));
            }
            boolean pass = count == null || count <= limit;
            if (!pass) {
                log.warn("[RateLimitService] 触发限流 key={}, count={}, limit={}", fullKey, count, limit);
            }
            return pass;
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            // 限流器降级：Redis 挂了放行，不让限流成为主链路新故障点
            log.warn("[RateLimitService] Redis 异常，限流降级放行 key={}, err={}", fullKey, e.getMessage());
            return true;
        }
    }
}