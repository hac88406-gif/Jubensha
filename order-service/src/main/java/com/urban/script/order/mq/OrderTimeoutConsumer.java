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

/**
 * 超时关单消费者 —— 监听 order.dlx.queue（15 分钟到期的延迟消息）
 *
 * <p>完整链路：
 * <pre>
 *   下单成功 → DelayCancelProducer 发送 Map JSON 到 order.delay.exchange
 *             → order.delay.queue（expiration=15min）
 *             → 15min 后到期，DLX 自动转到 order.dlx.queue
 *             → OrderTimeoutConsumer 消费执行关单
 * </pre>
 *
 * <p>消费者侧幂等（关键设计！）：
 * <ul>
 *   <li>用 {@code updateStatusAndCancelReason ... WHERE status = 0} 原子更新</li>
 *   <li>返回 0 行 = 订单已被其他路径（手动取消 / 场次关闭）处理过 → 跳过</li>
 *   <li>不设计"取消延迟消息"的接口（DLX+TTL 方案无法精确删除）</li>
 * </ul>
 *
 * <p>ACK 策略：
 * <ul>
 *   <li>业务处理成功 → basicAck（正常消费完成）</li>
 *   <li>业务异常（非幂等跳过）→ basicNack requeue=false → 消息被 RabbitMQ 丢弃
 *       （order.dlx.queue 不再配 DLX，不需要无限重试；日志留痕即可）</li>
 * </ul>
 *
 * @author urban-script-reservation
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutConsumer {

    private final OrderMapper orderMapper;
    private final SessionMapper sessionMapper;
    private final StockService stockService;
    private final ObjectMapper objectMapper;

    /**
     * 监听 order.dlx.queue —— 超时关单
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ORDER_DLX)
    public void handleTimeout(Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody());

        try {
            log.info("[OrderTimeoutConsumer] 收到超时关单消息 body={}", body);

            // ① 解析 JSON
            JsonNode node = objectMapper.readTree(body);
            String orderNo = node.path("orderNo").asText();
            long sessionId = node.path("sessionId").asLong();

            if (orderNo.isBlank()) {
                log.warn("[OrderTimeoutConsumer] 消息体缺少 orderNo，丢弃");
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ② 查订单（拿到 playerCnt 用于回滚库存）
            OrderInfo order = orderMapper.selectByOrderNo(orderNo);
            if (order == null) {
                log.warn("[OrderTimeoutConsumer] 订单不存在 orderNo={}，可能已被清理，ACK 跳过", orderNo);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ③ 幂等保护：原子更新 status → 2（只有 status=0 才能被改掉）
            int rows = orderMapper.updateStatusAndCancelReason(orderNo, 2, "TIMEOUT");
            if (rows == 0) {
                // 0 行 = 已经被其他路径处理过（手动取消 / 场次关闭）
                log.info("[OrderTimeoutConsumer] 订单已被处理，幂等跳过 orderNo={}, currentStatus={}",
                        orderNo, order.getStatus());
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ④ 释放 Redis + MySQL booked
            try {
                stockService.rollbackStock(sessionId, order.getPlayerCnt());
            } catch (Exception redisEx) {
                log.warn("[OrderTimeoutConsumer] Redis 回滚失败 orderNo={}, Redis 挂了也不影响，MySQL 兜底",
                        orderNo, redisEx);
            }

            // MySQL 回滚（双重保险）
            try {
                sessionMapper.decrementBookedIfEnough(sessionId, order.getPlayerCnt());
            } catch (Exception dbEx) {
                // 订单此时已置为已取消(2)，消息即将 ACK：若这里抛异常导致重试，
                // 重走的 UPDATE ... WHERE status=0 会返回 0 行——booked 永无机会再回滚。
                // 因此这里捕获后记 error 日志，提示人工核查库存（正常 MySQL 下不会发生）。
                log.error("[OrderTimeoutConsumer] MySQL 回滚 booked 失败，需人工核查库存 "
                                + "orderNo={}, sessionId={}, playerCnt={}",
                        orderNo, sessionId, order.getPlayerCnt(), dbEx);
            }

            log.info("[OrderTimeoutConsumer] ✅ 超时关单成功 orderNo={}, sessionId={}, playerCnt={}",
                    orderNo, sessionId, order.getPlayerCnt());

            // ⑤ 手动 ACK
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("[OrderTimeoutConsumer] 超时关单处理异常，body={}, deliveryTag={}", body, deliveryTag, e);
            try {
                // requeue=false：不重新入队（order.dlx.queue 没配 DLX，直接丢弃）
                // 避免异常消息无限循环刷屏日志
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ioEx) {
                log.error("[OrderTimeoutConsumer] basicNack 也失败了", ioEx);
            }
        }
    }
}
