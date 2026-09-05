package com.urban.script.order.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * RabbitMQ 配置 —— 订单服务专用链路
 *
 * <p>包含两条链路：
 * <ol>
 *   <li><b>延迟关单链路（order-service 独有）</b>：下单 → 15min 后到期 → DeadLetterConsumer 执行关单</li>
 *   <li><b>场次关闭通知链路（与 shop-service 共用）</b>：shop-service 关场次 → order-service 批量取消未支付订单</li>
 * </ol>
 *
 * <p>注意：场次关闭链路的 Exchange / Queue 参数必须与 shop-service 的 RabbitMQConfig <b>完全一致</b>，
 * 否则同名 Queue 被两次声明会因参数不同导致启动报错。
 *
 * @author urban-script-reservation
 */
@Configuration
public class RabbitMQConfig {

    // ========================================================================
    // 链路 1：延迟关单（order-service 独有）
    // ========================================================================

    /** 延迟关单 Exchange（direct + x-delayed-type 让 RabbitMQ 识别 expiration 头） */
    public static final String EXCHANGE_ORDER_DELAY = "order.delay.exchange";
    /** 延迟关单 Queue（消息到期后转发到 DLX） */
    public static final String QUEUE_ORDER_DELAY = "order.delay.queue";
    /** 延迟路由键 */
    public static final String ROUTING_KEY_ORDER_DELAY = "order.delay.ttl";

    /** 延迟 DLX */
    public static final String EXCHANGE_ORDER_DLX = "order.dlx.exchange";
    /** 延迟 DLX Queue（Consumer 从这里取到期消息执行关单） */
    public static final String QUEUE_ORDER_DLX = "order.dlx.queue";
    /** DLX 路由键 */
    public static final String ROUTING_KEY_ORDER_DLX = "order.dlx";

    // ========================================================================
    // 链路 2：场次关闭通知（与 shop-service 共用，参数必须完全一致）
    // ========================================================================

    /** 场次关闭业务 Exchange（与 shop-service 同名同参数） */
    public static final String EXCHANGE_SESSION_CLOSE = "session.close.exchange";
    /** 场次关闭业务 Queue（与 shop-service 同名同参数） */
    public static final String QUEUE_SESSION_CLOSE = "session.close.queue";
    /** 场次关闭业务 RoutingKey */
    public static final String ROUTING_KEY_ORDER_CANCEL = "order.cancel";

    /** 场次关闭 DLX */
    public static final String DLX_EXCHANGE_SESSION_CLOSE = "session.close.dlx.exchange";
    /** 场次关闭 DLX Queue */
    public static final String DLX_QUEUE_SESSION_CLOSE = "session.close.dlx.queue";

    // ========================================================================
    // 链路 1 Bean：延迟关单拓扑
    // ========================================================================

    /**
     * 延迟关单 Exchange —— direct 类型
     * <p>用 RabbitMQ 原生 expiration 机制（不是 delayed-message 插件），不额外装插件即可工作。
     */
    @Bean
    public DirectExchange orderDelayExchange() {
        return new DirectExchange(EXCHANGE_ORDER_DELAY, true, false);
    }

    /**
     * 延迟关单 Queue —— 到期后转发到 DLX
     */
    @Bean
    public Queue orderDelayQueue() {
        Map<String, Object> args = Map.of(
                "x-dead-letter-exchange", EXCHANGE_ORDER_DLX,
                "x-dead-letter-routing-key", ROUTING_KEY_ORDER_DLX
        );
        return QueueBuilder.durable(QUEUE_ORDER_DELAY)
                .withArguments(args)
                .build();
    }

    /** 延迟 Queue → 延迟 Exchange 的 Binding */
    @Bean
    public Binding orderDelayBinding() {
        return BindingBuilder
                .bind(orderDelayQueue())
                .to(orderDelayExchange())
                .with(ROUTING_KEY_ORDER_DELAY);
    }

    /** 死信 Exchange */
    @Bean
    public DirectExchange orderDlxExchange() {
        return new DirectExchange(EXCHANGE_ORDER_DLX, true, false);
    }

    /**
     * 死信 Queue —— DeadLetterConsumer 监听此队列
     * <p>注意：这里 <b>不再配 DLX</b>，消费者 nack requeue=false 的消息会被 RabbitMQ 直接丢弃。
     * 设计意图：消息已经业务处理过（可能成功 / 可能因幂等跳过），不需要无限重试。
     */
    @Bean
    public Queue orderDlxQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_DLX).build();
    }

    /** 死信 Binding */
    @Bean
    public Binding orderDlxBinding() {
        return BindingBuilder
                .bind(orderDlxQueue())
                .to(orderDlxExchange())
                .with(ROUTING_KEY_ORDER_DLX);
    }

    // ========================================================================
    // 链路 2 Bean：场次关闭通知拓扑（参数与 shop-service 完全一致）
    // ========================================================================

    /** 业务 Exchange */
    @Bean
    public DirectExchange sessionCloseExchange() {
        return new DirectExchange(EXCHANGE_SESSION_CLOSE, true, false);
    }

    /** 业务 Queue（带 DLX，参数与 shop-service 完全一致） */
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

    /** 业务 Binding */
    @Bean
    public Binding sessionCloseBinding() {
        return BindingBuilder
                .bind(sessionCloseQueue())
                .to(sessionCloseExchange())
                .with(ROUTING_KEY_ORDER_CANCEL);
    }

    /** 死信 Exchange */
    @Bean
    public DirectExchange sessionCloseDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE_SESSION_CLOSE, true, false);
    }

    /** 死信 Queue */
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
