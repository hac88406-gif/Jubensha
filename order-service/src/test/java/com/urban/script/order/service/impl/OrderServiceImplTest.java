package com.urban.script.order.service.impl;

import com.urban.script.common.BusinessException;
import com.urban.script.common.R;
import com.urban.script.order.dto.OrderCreateReq;
import com.urban.script.order.entity.OrderInfo;
import com.urban.script.order.feign.ShopClient;
import com.urban.script.order.mapper.OrderMapper;
import com.urban.script.order.mapper.SessionMapper;
import com.urban.script.order.producer.DelayCancelProducer;
import com.urban.script.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderServiceImpl 单元测试 —— 下单主链路（锁 / Feign 校验 / Lua 扣减 / MySQL 降级）+ 取消幂等
 *
 * @author urban-script-reservation
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderServiceImpl · 下单/取消核心链路")
class OrderServiceImplTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private SessionMapper sessionMapper;
    @Mock
    private ShopClient shopClient;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private com.urban.script.order.service.StockService stockService;
    @Mock
    private DelayCancelProducer delayCancelProducer;
    @Mock
    private com.urban.script.order.service.RateLimitService rateLimitService;
    @Mock
    private RLock lock;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(
                orderMapper, sessionMapper, shopClient, redissonClient,
                stockService, delayCancelProducer, rateLimitService);
        // 限流默认放行（限流本身的用例单独 stub）
        // lenient：cancelOrder 等不走 createOrder 的用例不会用到该 stub，允许备用
        lenient().when(rateLimitService.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    }

    /** 构造一个未来开放的场次 Feign 响应 */
    private ShopClient.SessionFeignRes openSession() {
        ShopClient.SessionFeignRes s = new ShopClient.SessionFeignRes();
        s.setId(100L);
        s.setScriptId(10L);
        s.setShopId(20L);
        s.setSessionDate(LocalDate.now().plusDays(1));
        s.setStartTime(LocalTime.of(10, 0));
        s.setEndTime(LocalTime.of(12, 0));
        s.setStatus(1);
        s.setCapacity(6);
        s.setBooked(0);
        s.setPrice(new BigDecimal("88.00"));
        return s;
    }

    /** 构造标准下单请求 */
    private OrderCreateReq req(int playerCnt) {
        OrderCreateReq r = new OrderCreateReq();
        r.setSessionId(100L);
        r.setPlayerCnt(playerCnt);
        return r;
    }

    /** 注入 Redisson 锁 mock（tryLock 成功 + 当前线程持有） */
    private void stubLock() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            // mock 场景不会真中断，仅满足检查型异常编译要求
            throw new RuntimeException(e);
        }
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    @DisplayName("重复下单拦截：同一用户同场次已有未取消订单 → 抛业务异常")
    void createOrder_shouldReject_whenDuplicateOrder() {
        stubLock();
        OrderInfo existing = new OrderInfo();
        existing.setOrderNo("ORD_OLD");
        existing.setStatus(0);
        when(orderMapper.selectActiveByUserAndSession(anyLong(), anyLong())).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createOrder(1L, req(1)));

        assertTrue(ex.getMessage().contains("已预约过该场次"));
        verify(orderMapper, never()).insert(any());
    }

    @Test
    @DisplayName("Lua 扣减失败（名额已满）→ 抛「场次名额已满」")
    void createOrder_shouldFail_whenStockFull() {
        stubLock();
        when(orderMapper.selectActiveByUserAndSession(anyLong(), anyLong())).thenReturn(null);
        when(shopClient.getSession(anyLong())).thenReturn(R.ok(openSession()));
        when(stockService.decrStock(anyLong(), anyInt())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createOrder(1L, req(1)));

        assertTrue(ex.getMessage().contains("场次名额已满"));
        verify(orderMapper, never()).insert(any());
    }

    @Test
    @DisplayName("Redis 不可用 → 降级 MySQL 乐观锁成功，金额 = price × playerCnt")
    void createOrder_shouldFallbackToMysql_whenRedisDown() {
        stubLock();
        when(orderMapper.selectActiveByUserAndSession(anyLong(), anyLong())).thenReturn(null);
        when(shopClient.getSession(anyLong())).thenReturn(R.ok(openSession()));
        when(stockService.decrStock(anyLong(), anyInt()))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));
        when(sessionMapper.incrementBookedIfEnough(100L, 2)).thenReturn(1);

        String orderNo = orderService.createOrder(1L, req(2));

        assertNotNull(orderNo);
        assertTrue(orderNo.startsWith("ORD"));
        // 降级路径不应走 Redis 同步 incrementBooked
        verify(sessionMapper, never()).incrementBooked(anyLong(), anyInt());
        // 金额 = 88.00 × 2
        ArgumentCaptor<OrderInfo> captor = ArgumentCaptor.forClass(OrderInfo.class);
        verify(orderMapper).insert(captor.capture());
        assertEquals(0, new BigDecimal("176.00").compareTo(captor.getValue().getAmount()));
        // 延迟关单消息仍要发
        verify(delayCancelProducer).sendDelayCancel(anyString(), anyLong());
    }

    @Test
    @DisplayName("Redis+Lua 正常路径 → insert + MySQL 同步 booked + 发延迟关单")
    void createOrder_shouldSucceed_whenRedisPath() {
        stubLock();
        when(orderMapper.selectActiveByUserAndSession(anyLong(), anyLong())).thenReturn(null);
        when(shopClient.getSession(anyLong())).thenReturn(R.ok(openSession()));
        when(stockService.decrStock(anyLong(), anyInt())).thenReturn(true);
        when(orderMapper.insert(any(OrderInfo.class))).thenAnswer(inv -> 1);

        String orderNo = orderService.createOrder(1L, req(1));

        assertNotNull(orderNo);
        verify(sessionMapper).incrementBooked(100L, 1);
        verify(delayCancelProducer).sendDelayCancel(anyString(), anyLong());
        ArgumentCaptor<OrderInfo> captor = ArgumentCaptor.forClass(OrderInfo.class);
        verify(orderMapper).insert(captor.capture());
        assertEquals(0, new BigDecimal("88.00").compareTo(captor.getValue().getAmount()));
        verify(stockService, never()).forceRefreshBooked(anyLong());
    }

    @Test
    @DisplayName("取消订单幂等：订单已支付/已取消 → 直接跳过不回滚")
    void cancelOrder_shouldSkip_whenNotPending() {
        OrderInfo paid = new OrderInfo();
        paid.setId(1L);
        paid.setStatus(1);   // 已支付
        when(orderMapper.selectById(1L)).thenReturn(paid);

        orderService.cancelOrder(1L, "TEST", "UNIT");

        verify(orderMapper, never()).updateStatusToCancel(anyString(), any());
        verify(stockService, never()).rollbackStock(anyLong(), anyInt());
        verify(sessionMapper, never()).decrementBookedIfEnough(anyLong(), anyInt());
    }

    @Test
    @DisplayName("取消订单正常路径：仅待支付(0) 才取消 → 原子取消 + 双写回滚库存")
    void cancelOrder_shouldCancel_whenPending() {
        OrderInfo pending = new OrderInfo();
        pending.setId(1L);
        pending.setOrderNo("ORD_CANCEL_1");
        pending.setStatus(0);
        pending.setSessionId(100L);
        pending.setPlayerCnt(2);
        when(orderMapper.selectById(1L)).thenReturn(pending);
        when(orderMapper.updateStatusToCancel("ORD_CANCEL_1", "USER_CANCEL")).thenReturn(1);
        when(sessionMapper.decrementBookedIfEnough(100L, 2)).thenReturn(1);

        orderService.cancelOrder(1L, "USER_CANCEL", "ROLE_PLAYER:1");

        verify(orderMapper).updateStatusToCancel("ORD_CANCEL_1", "USER_CANCEL");
        verify(stockService).rollbackStock(100L, 2);
        verify(sessionMapper).decrementBookedIfEnough(100L, 2);
    }

    @Test
    @DisplayName("取消订单竞态：原子更新返回 0 行（已被支付/他处取消）→ 幂等跳过不回滚")
    void cancelOrder_shouldSkip_whenAtomicUpdateReturnsZero() {
        OrderInfo pending = new OrderInfo();
        pending.setId(1L);
        pending.setOrderNo("ORD_CANCEL_2");
        pending.setStatus(0);
        pending.setSessionId(100L);
        pending.setPlayerCnt(2);
        when(orderMapper.selectById(1L)).thenReturn(pending);
        // 模拟竞态：select 时还是待支付，但 UPDATE ... WHERE status=0 时已被支付回调抢先改为已支付
        when(orderMapper.updateStatusToCancel("ORD_CANCEL_2", "USER_CANCEL")).thenReturn(0);

        orderService.cancelOrder(1L, "USER_CANCEL", "ROLE_PLAYER:1");

        verify(orderMapper).updateStatusToCancel("ORD_CANCEL_2", "USER_CANCEL");
        verify(stockService, never()).rollbackStock(anyLong(), anyInt());
        verify(sessionMapper, never()).decrementBookedIfEnough(anyLong(), anyInt());
    }

    @Test
    @DisplayName("抢位限流：超过窗口阈值 → 抛「操作过于频繁」且不进入下单链路")
    void createOrder_shouldReject_whenRateLimited() {
        // 限流器返回 false：提示调用方拒绝本次请求
        when(rateLimitService.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createOrder(1L, req(1)));

        assertTrue(ex.getMessage().contains("操作过于频繁"));
        // 未走到任何下单逻辑：不抢锁、不查重、不扣库存、不落单
        verify(redissonClient, never()).getLock(anyString());
        verify(orderMapper, never()).selectActiveByUserAndSession(anyLong(), anyLong());
        verify(stockService, never()).decrStock(anyLong(), anyInt());
        verify(orderMapper, never()).insert(any());
    }
}