package com.urban.script.shop.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * RabbitMQ 配置 —— 场次关闭通知链路
 *
 * <p>拓扑：
 * <pre>
 * Producer (shop-service.closeSession)
 *     │  routingKey = order.cancel
 *     ▼
 * session.close.exchange (DirectExchange, durable)
 *     │  binding routingKey = order.cancel
 *     ▼
 * session.close.queue (durable, 带 DLX 转发)
 *     │  消费者: order-service 批量取消未支付订单
 *     │
 *     │  ── 消费失败 / 超过重试次数 ──► DLX 链路
 *     ▼
 * session.close.dlx.exchange → session.close.dlx.queue
 * </pre>
 *
 * <p>队列参数：
 * <ul>
 *   <li>x-dead-letter-exchange: 死信交换机名称</li>
 *   <li>x-dead-letter-routing-key: 死信路由键（此处复用原 routingKey）</li>
 * </ul>
 *
 * @author urban-script-reservation
 */
@Configuration
public class RabbitMQConfig {

    /** 业务 Exchange：场次关闭 */
    public static final String EXCHANGE_SESSION_CLOSE = "session.close.exchange";
    /** 业务 Queue */
    public static final String QUEUE_SESSION_CLOSE = "session.close.queue";
    /** 业务 RoutingKey */
    public static final String ROUTING_KEY_ORDER_CANCEL = "order.cancel";

    /** 死信 Exchange */
    public static final String DLX_EXCHANGE_SESSION_CLOSE = "session.close.dlx.exchange";
    /** 死信 Queue */
    public static final String DLX_QUEUE_SESSION_CLOSE = "session.close.dlx.queue";

    // ===================== 业务链路 =====================

    /** 业务 DirectExchange（持久化） */
    @Bean
    public DirectExchange sessionCloseExchange() {
        // durable=true: 重启后不丢；autoDelete=false: 不自动删除
        return new DirectExchange(EXCHANGE_SESSION_CLOSE, true, false);
    }

    /**
     * 业务 Queue（持久化 + DLX 配置）。
     *
     * <p>x-dead-letter-* 参数：当消息变成死信（消费异常且 nack 不 requeue、或 TTL 过期、或队列满被挤出）时，
     * RabbitMQ 自动将消息转发到指定的死信交换机 + 路由键。
     */
    @Bean
    public Queue sessionCloseQueue() {
        Map<String, Object> args = Map.of(
                "x-dead-letter-exchange", DLX_EXCHANGE_SESSION_CLOSE,
                "x-dead-letter-routing-key", ROUTING_KEY_ORDER_CANCEL
        );
        return QueueBuilder.durable(QUEUE_SESSION_CLOSE)
                .withArguments(args)
                .build();
    }

    /** 业务 Binding：Exchange ──(routingKey)──► Queue */
    @Bean
    public Binding sessionCloseBinding() {
        return BindingBuilder
                .bind(sessionCloseQueue())
                .to(sessionCloseExchange())
                .with(ROUTING_KEY_ORDER_CANCEL);
    }

    // ===================== 死信链路 =====================

    /** 死信 DirectExchange（持久化） */
    @Bean
    public DirectExchange sessionCloseDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE_SESSION_CLOSE, true, false);
    }

    /** 死信 Queue（持久化，无人消费也无妨） */
    @Bean
    public Queue sessionCloseDlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE_SESSION_CLOSE).build();
    }

    /** 死信 Binding */
    @Bean
    public Binding sessionCloseDlxBinding() {
        return BindingBuilder
                .bind(sessionCloseDlxQueue())
                .to(sessionCloseDlxExchange())
                .with(ROUTING_KEY_ORDER_CANCEL);
    }
}
