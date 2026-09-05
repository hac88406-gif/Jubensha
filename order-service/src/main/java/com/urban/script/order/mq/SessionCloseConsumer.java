package com.urban.script.order.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.urban.script.order.config.RabbitMQConfig;
import com.urban.script.order.entity.OrderInfo;
import com.urban.script.order.mapper.OrderMapper;
import com.urban.script.order.mapper.SessionMapper;
import com.urban.script.order.service.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 场次关闭批量取消消费者 —— 监听 session.close.queue
 *
 * <p>触发场景：shop-service 的 closeSession() 关闭场次后发送消息，
 * order-service 批量取消该场次所有未支付（status=0）的订单。
 *
 * <p>消息格式（shop-service 发送）：
 * <pre>
 * {
 *   "sessionId": 123,
 *   "shopId": 456,
 *   "playerCntTotal": 4,
 *   "cancelReason": "SESSION_CLOSED",
 *   "operator": 1
 * }
 * </pre>
 *
 * <p>与超时关单消费者的协作关系（幂等设计）：
 * <ul>
 *   <li>两个消费者都可能处理同一订单（先到先处理）</li>
 *   <li>{@code updateStatusAndCancelReason ... WHERE status = 0} 保证只有一个能成功</li>
 *   <li>另一个消费者返回 0 行后跳过（幂等）</li>
 * </ul>
 *
 * @author urban-script-reservation
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCloseConsumer {

    private final OrderMapper orderMapper;
    private final SessionMapper sessionMapper;
    private final StockService stockService;
    private final ObjectMapper objectMapper;

    /**
     * 监听 session.close.queue —— 场次关闭批量取消订单
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_SESSION_CLOSE)
    public void handleSessionClose(Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody());

        try {
            log.info("[SessionCloseConsumer] 收到场次关闭消息 body={}", body);

            // ① 解析 JSON
            JsonNode node = objectMapper.readTree(body);
            long sessionId = node.path("sessionId").asLong();
            String cancelReason = node.path("cancelReason").asText("SESSION_CLOSED");

            if (sessionId <= 0) {
                log.warn("[SessionCloseConsumer] 消息体缺少 sessionId，丢弃");
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ② 查该场次所有待支付订单
            List<OrderInfo> pendingOrders = orderMapper.selectPendingBySession(sessionId);
            if (pendingOrders == null || pendingOrders.isEmpty()) {
                log.info("[SessionCloseConsumer] sessionId={} 无待支付订单，幂等 ACK", sessionId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ③ 逐个取消（每个订单独立幂等）
            int successCount = 0;
            int skipCount = 0;

            for (OrderInfo order : pendingOrders) {
                int rows = orderMapper.updateStatusAndCancelReason(order.getOrderNo(), 2, cancelReason);
                if (rows == 0) {
                    // 已被其他消费者处理过（超时关单 / 手动取消）
                    skipCount++;
                    log.debug("[SessionCloseConsumer] 订单已被处理，跳过 orderNo={}", order.getOrderNo());
                    continue;
                }

                // 释放 Redis booked
                try {
                    stockService.rollbackStock(sessionId, order.getPlayerCnt());
                } catch (Exception e) {
                    log.warn("[SessionCloseConsumer] Redis 回滚失败，orderNo={}", order.getOrderNo(), e);
                }

                // MySQL 回滚 booked（双重保险）
                sessionMapper.decrementBookedIfEnough(sessionId, order.getPlayerCnt());

                successCount++;
            }

            log.info("[SessionCloseConsumer] ✅ 场次关闭处理完成 sessionId={}, 成功={}, 跳过={}, 总数={}",
                    sessionId, successCount, skipCount, pendingOrders.size());

            // ④ 手动 ACK
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("[SessionCloseConsumer] 场次关闭处理异常 body={}, deliveryTag={}", body, deliveryTag, e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ioEx) {
                log.error("[SessionCloseConsumer] basicNack 也失败了", ioEx);
            }
        }
    }
}
