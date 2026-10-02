package com.urban.script.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.urban.script.order.entity.OrderInfo;
import com.urban.script.order.mapper.OrderMapper;
import com.urban.script.order.mapper.SessionMapper;
import com.urban.script.order.service.MqDedupService;
import com.urban.script.order.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderTimeoutConsumer 单元测试 —— 超时关单"双重幂等"
 *
 * <p>核心断言（双重幂等）：
 * <ul>
 *   <li>消息级幂等：去重表 {@code tryConsume} 返回 false（同一条消息重复投递）
 *       → ACK 跳过，不进业务逻辑</li>
 *   <li>业务级幂等：{@code updateStatusAndCancelReason ... WHERE status=0} 返回 0 行
 *       = 订单已被其他路径（支付 / 手动取消 / 场次关闭）处理 → ACK 跳过，<b>绝不回滚库存</b></li>
 *   <li>订单不存在 / 消息体缺 orderNo → ACK 丢弃，不留死信</li>
 *   <li>正常关单 → Redis(Lua) + MySQL booked 双回滚 + ACK</li>
 *   <li>消息体非法 JSON → basicNack requeue=false（不无限重试刷日志）</li>
 * </ul>
 *
 * @author urban-script-reservation
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderTimeoutConsumer · 超时关单幂等")
class OrderTimeoutConsumerTest {

    private static final long DELIVERY_TAG = 42L;

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private SessionMapper sessionMapper;
    @Mock
    private StockService stockService;
    @Mock
    private MqDedupService mqDedupService;

    private OrderTimeoutConsumer consumer;
    private Channel channel;

    @BeforeEach
    void setUp() {
        // ObjectMapper 用真实实现，保证 JSON 解析行为与生产一致
        consumer = new OrderTimeoutConsumer(orderMapper, sessionMapper, stockService,
                new ObjectMapper(), mqDedupService);
        channel = mock(Channel.class);
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    /** 构造一条带 deliveryTag 的延迟消息 */
    private Message msg(String body) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(DELIVERY_TAG);
        return new Message(body.getBytes(), props);
    }

    private String timeoutBody(String orderNo) {
        return "{\"orderNo\":\"" + orderNo + "\",\"sessionId\":100}";
    }

    /** 构造一张待支付订单（selectByOrderNo 返回） */
    private OrderInfo pendingOrder() {
        OrderInfo order = new OrderInfo();
        order.setId(1L);
        order.setOrderNo("ORD_TIMEOUT_1");
        order.setSessionId(100L);
        order.setPlayerCnt(2);
        order.setStatus(0);
        return order;
    }

    /**
     * 消息级幂等放行：视为"首次消费"（未命中去重表）
     *
     * <p>只给需要走到业务逻辑的用例调用；缺 orderNo / 非法 JSON 的用例在
     * 去重之前就 return 了，调用它会触发 Mockito 严格模式的 UnnecessaryStubbing。
     */
    private void givenFirstConsume() {
        when(mqDedupService.tryConsume(anyString(), anyString())).thenReturn(true);
    }

    // ========================================================================
    // 用例 ⓪ —— 消息级幂等：去重表命中（同一条消息重复投递）
    // ========================================================================

    @Test
    @DisplayName("消息级幂等：去重表已存在该消息 → ACK 跳过且不进业务逻辑")
    void handleTimeout_shouldAckAndSkip_whenMessageAlreadyConsumed() throws Exception {
        // 去重表 INSERT 撞唯一键 → tryConsume 返回 false
        when(mqDedupService.tryConsume(anyString(), anyString())).thenReturn(false);

        consumer.handleTimeout(msg(timeoutBody("ORD_TIMEOUT_1")), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        // 去重拦截后不应再查库、不应回滚库存
        verify(orderMapper, never()).selectByOrderNo(anyString());
        verify(stockService, never()).rollbackStock(anyLong(), anyInt());
        verify(channel, never()).basicNack(anyLong(), eq(false), eq(false));
    }

    // ========================================================================
    // 用例 ① —— 幂等核心：订单已被支付/取消，绝不再回滚库存（P0）
    // ========================================================================

    @Test
    @DisplayName("幂等：订单已被处理（UPDATE 返回 0 行）→ ACK 跳过且不回滚库存")
    void handleTimeout_shouldAckAndSkip_whenOrderAlreadyProcessed() throws Exception {
        givenFirstConsume();
        OrderInfo order = pendingOrder();
        when(orderMapper.selectByOrderNo("ORD_TIMEOUT_1")).thenReturn(order);
        // 模拟竞态：select 时还是待支付，但 UPDATE ... WHERE status=0 时已被支付回调抢先改为 1
        when(orderMapper.updateStatusAndCancelReason("ORD_TIMEOUT_1", 2, "TIMEOUT")).thenReturn(0);

        consumer.handleTimeout(msg(timeoutBody("ORD_TIMEOUT_1")), channel);

        // 只 ACK，不落任何回滚副作用
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(stockService, never()).rollbackStock(anyLong(), anyInt());
        verify(sessionMapper, never()).decrementBookedIfEnough(anyLong(), anyInt());
        verify(channel, never()).basicNack(anyLong(), eq(false), eq(false));
    }

    // ========================================================================
    // 用例 ② —— 订单不存在 / 消息体缺 orderNo：直接 ACK 丢弃
    // ========================================================================

    @Test
    @DisplayName("订单不存在 → ACK 跳过（不留死信）")
    void handleTimeout_shouldAck_whenOrderNotFound() throws Exception {
        givenFirstConsume();
        when(orderMapper.selectByOrderNo("ORD_GONE")).thenReturn(null);

        consumer.handleTimeout(msg(timeoutBody("ORD_GONE")), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(orderMapper, never()).updateStatusAndCancelReason(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("消息体缺少 orderNo → WARN 后 ACK 丢弃")
    void handleTimeout_shouldAck_whenOrderNoBlank() throws Exception {
        consumer.handleTimeout(msg("{\"sessionId\":100}"), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(orderMapper, never()).selectByOrderNo(anyString());
        verify(channel, never()).basicNack(anyLong(), eq(false), eq(false));
    }

    // ========================================================================
    // 用例 ③ —— 正常关单：双写回滚库存 + ACK
    // ========================================================================

    @Test
    @DisplayName("正常关单：原子更新 1 行 → Redis + MySQL booked 双回滚 + ACK")
    void handleTimeout_shouldRollbackAndAck_whenClosed() throws Exception {
        givenFirstConsume();
        OrderInfo order = pendingOrder();
        when(orderMapper.selectByOrderNo("ORD_TIMEOUT_1")).thenReturn(order);
        when(orderMapper.updateStatusAndCancelReason("ORD_TIMEOUT_1", 2, "TIMEOUT")).thenReturn(1);

        consumer.handleTimeout(msg(timeoutBody("ORD_TIMEOUT_1")), channel);

        verify(stockService).rollbackStock(100L, 2);          // Redis 回滚
        verify(sessionMapper).decrementBookedIfEnough(100L, 2); // MySQL booked 回滚（双重保险）
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    /**
     * Redis 回滚抛异常不应阻断 MySQL 兜底与 ACK
     * （Rollback 逻辑：捕获 redisEx 记 WARN，MySQL 继续回滚，消息 ACK）
     */
    @Test
    @DisplayName("Redis 回滚异常 → 降级记 WARN，MySQL 兜底回滚且仍 ACK")
    void handleTimeout_shouldStillRollbackMysql_whenRedisDown() throws Exception {
        givenFirstConsume();
        OrderInfo order = pendingOrder();
        when(orderMapper.selectByOrderNo("ORD_TIMEOUT_1")).thenReturn(order);
        when(orderMapper.updateStatusAndCancelReason("ORD_TIMEOUT_1", 2, "TIMEOUT")).thenReturn(1);
        // rollbackStock 为 void 方法（返回值不可 stub），需用 doThrow 声明异常
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(stockService).rollbackStock(100L, 2);

        consumer.handleTimeout(msg(timeoutBody("ORD_TIMEOUT_1")), channel);

        // MySQL 兜底仍执行，消息正常 ACK（不会因 Redis 拖垮关单主链路）
        verify(sessionMapper).decrementBookedIfEnough(100L, 2);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    // ========================================================================
    // 用例 ④ —— 非法消息体：basicNack requeue=false（不无限重试刷日志）
    // ========================================================================

    @Test
    @DisplayName("非法 JSON 消息体 → basicNack(requeue=false) 丢弃且不 ACK")
    void handleTimeout_shouldNack_whenInvalidBody() throws Exception {
        consumer.handleTimeout(msg("not-a-json"), channel);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(DELIVERY_TAG, false);
        verify(orderMapper, never()).selectByOrderNo(anyString());
    }
}