package com.urban.script.order.service;

import com.urban.script.order.entity.SessionInfo;
import com.urban.script.order.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StockService 单元测试 —— Redis+Lua 原子扣减（含懒加载重试）
 *
 * <p>纯 Mockito，不启动 Spring 容器。mock StringRedisTemplate.execute(RedisCallback)
 * 模拟 Lua 脚本返回值：1=成功 0=名额不足 -1(null)=key 不存在。
 *
 * @author urban-script-reservation
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StockService · Redis+Lua 原子扣减")
class StockServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private SessionMapper sessionMapper;
    @Mock
    private RLock lock;

    private StockService stockService;

    @BeforeEach
    void setUp() {
        stockService = new StockService(stringRedisTemplate, redissonClient, sessionMapper);
    }

    /** mock execute 回调返回值（模拟 Lua 脚本执行结果一次或多次） */
    private void stubEval(Long... results) {
        if (results.length == 1) {
            when(stringRedisTemplate.execute(any(RedisCallback.class))).thenReturn(results[0]);
        } else {
            Long[] rest = new Long[results.length - 1];
            System.arraycopy(results, 1, rest, 0, rest.length);
            // thenReturn(value, values...)：value=首次结果，values=后续顺序返回
            when(stringRedisTemplate.execute(any(RedisCallback.class))).thenReturn(results[0], rest);
        }
    }

    /** 构造 lazyInit 所需的 Redisson / Redis / MySQL 依赖 */
    private void stubLazyInitEnv() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            // mock 场景不会真中断，仅满足检查型异常编译要求
            throw new RuntimeException(e);
        }
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);

        @SuppressWarnings("unchecked")
        HashOperations<String, String, String> hashOps = org.mockito.Mockito.mock(HashOperations.class);
        // 用 doReturn 规避 thenReturn 在泛型方法上的静态类型推断歧义
        doReturn(hashOps).when(stringRedisTemplate).opsForHash();
        doReturn(true).when(stringRedisTemplate).expire(anyString(), anyLong(), any(TimeUnit.class));

        SessionInfo session = new SessionInfo();
        session.setId(1L);
        session.setCapacity(10);
        session.setBooked(0);
        session.setStatus(1);
        session.setSessionDate(LocalDate.now().plusDays(1));
        session.setStartTime(LocalTime.NOON);
        session.setEndTime(LocalTime.NOON.plusHours(2));
        when(sessionMapper.selectById(1L)).thenReturn(session);
    }

    @Test
    @DisplayName("Lua 返回 1 → 扣减成功")
    void decr_shouldReturnTrue_whenLuaReturns1() {
        stubEval(1L);

        boolean ok = stockService.decrStock(1L, 2);

        assertTrue(ok, "Lua 返回 1 应扣减成功");
    }

    @Test
    @DisplayName("Lua 返回 0 → 名额不足")
    void decr_shouldReturnFalse_whenLuaReturns0() {
        stubEval(0L);

        boolean ok = stockService.decrStock(1L, 2);

        assertFalse(ok, "Lua 返回 0 应判定名额不足");
    }

    @Test
    @DisplayName("key 不存在 → 懒加载 MySQL 后重试成功")
    void decr_shouldLazyInitAndRetry_whenKeyMissing() {
        stubLazyInitEnv();
        // 第一次 eval 返回 null（key 不存在触发懒加载），第二次返回 1（重试成功）
        stubEval(null, 1L);

        boolean ok = stockService.decrStock(1L, 2);

        assertTrue(ok, "懒加载后重试应扣减成功");
        verify(sessionMapper).selectById(1L);
        verify(stringRedisTemplate).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("懒加载失败（MySQL 无场次）→ 直接失败不重试")
    void decr_shouldFail_whenLazyInitFails() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
        when(sessionMapper.selectById(1L)).thenReturn(null);   // 场次不存在

        // 每次 eval 都返回 null（key 不存在）→ 懒加载一直失败
        when(stringRedisTemplate.execute(any(RedisCallback.class))).thenReturn(null);

        boolean ok = stockService.decrStock(1L, 2);

        assertFalse(ok, "懒加载失败应返回 false");
    }

    @Test
    @DisplayName("playerCnt <= 0 → 抛 IllegalArgumentException")
    void decr_shouldThrow_whenPlayerCntInvalid() {
        try {
            stockService.decrStock(1L, 0);
        } catch (IllegalArgumentException expected) {
            // 符合预期
            return;
        }
        throw new AssertionError("期望抛 IllegalArgumentException");
    }
}