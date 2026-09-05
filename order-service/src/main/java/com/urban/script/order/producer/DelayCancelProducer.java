package com.urban.script.order.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urban.script.order.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 延迟关单消息发送器
 *
 * <p>下单成功后发送延迟消息到 RabbitMQ，15 分钟后消息到期进入死信队列，
 * 由 DeadLetterConsumer 消费执行关单逻辑：
 * <pre>
 *   查订单 status=0 → 改为 2（已取消）
 *                  → 回滚 Redis + MySQL booked
 *   查订单已支付/已取消 → 跳过（幂等）
 * </pre>
 *
 * <p>发送方式：AMQP 原生 expiration header（毫秒），RabbitMQ 原生支持 TTL 机制，
 * 消息到期后自动路由到 DLX。
 *
 * <p>消息体（JSON）：{ "orderNo": "ORDxxx", "sessionId": 123 }
 * 包含 sessionId 是因为关单时需要回滚场次 booked 数。
 *
 * @author urban-script-reservation
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelayCancelProducer {

    private final RabbitTemplate rabbitTemplate;
    /** 消费者（OrderTimeoutConsumer）手动 new String(body) + Jackson 解析，
     *  因此这里必须发 JSON 文本而不是 Map 对象 —— 默认 SimpleMessageConverter 会把 Map 做
     *  JDK 序列化（二进制），消费端 readTree 直接解析失败 → basicNack 丢弃 → 订单永不关单 */
    private final ObjectMapper objectMapper;

    /**
     * 默认延迟：15 分钟（玩家支付窗口期）
     * ⚠️ 冒烟测试临时改为 30 秒（Step19），生产值需还原为 15*60*1000L
     */
    private static final long DEFAULT_DELAY_MS = 30 * 1000L;

    /**
     * 发送延迟关单消息（默认 15 分钟后到期）
     *
     * @param orderNo   订单号（幂等键）
     * @param sessionId 场次 ID（关单回滚 booked 需要）
     */
    public void sendDelayCancel(String orderNo, Long sessionId) {
        sendDelayCancel(orderNo, sessionId, DEFAULT_DELAY_MS);
    }

    /**
     * 发送延迟关单消息（自定义延迟）
     *
     * @param orderNo   订单号
     * @param sessionId 场次 ID
     * @param delayMs   延迟毫秒数
     */
    public void sendDelayCancel(String orderNo, Long sessionId, long delayMs) {
        // 构造消息体（Map → JSON 字符串；与 shop-service SessionClose 链路保持一致）
        Map<String, Object> payload = new HashMap<>(4);
        payload.put("orderNo", orderNo);
        payload.put("sessionId", sessionId);
        payload.put("timestamp", System.currentTimeMillis());

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("[DelayCancelProducer] JSON 序列化失败 orderNo={}, sessionId={}", orderNo, sessionId, e);
            return;
        }

        // 关键：设置 expiration header 让 RabbitMQ 延迟投递
        // RabbitMQ 原生 TTL 机制，消息在队列里最多等 delayMs，到期后转发到 DLX
        MessagePostProcessor delayProcessor = msg -> {
            msg.getMessageProperties().setExpiration(String.valueOf(delayMs));
            return msg;
        };

        try {
            // 注意：发送的是 json 字符串（text/plain），消费端按纯文本 JSON 解析。
            // 绝不能直接发 Map —— SimpleMessageConverter 会 JDK 序列化（Bug②）
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_ORDER_DELAY,      // exchange
                    RabbitMQConfig.ROUTING_KEY_ORDER_DELAY,  // routingKey
                    json,                                     // message body（JSON 文本）
                    delayProcessor                            // 设置 expiration
            );
            log.info("[DelayCancelProducer] 已发送延迟关单消息 orderNo={}, sessionId={}, delay={}ms",
                    orderNo, sessionId, delayMs);
        } catch (Exception e) {
            // 延迟消息发送失败不阻塞主链路——订单已创建成功，
            // 最差情况：没有自动关单，需要手动取消或场次关闭时统一处理
            log.error("[DelayCancelProducer] 发送延迟关单消息失败 orderNo={}, sessionId={}, 但不影响主链路",
                    orderNo, sessionId, e);
        }
    }
}
