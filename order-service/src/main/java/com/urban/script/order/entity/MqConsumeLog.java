package com.urban.script.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * MQ 消费去重日志实体 —— 映射 mq_consume_log 表
 *
 * <p>作用：防止 RabbitMQ 因网络抖动 / 消费者重启导致同一条消息被重复消费。
 * 去重键为 (consumer_name, biz_key) 联合唯一索引：
 * <ul>
 *   <li>超时关单链路：consumer_name='OrderTimeoutConsumer', biz_key=orderNo</li>
 *   <li>场次关闭链路：consumer_name='SessionCloseConsumer', biz_key=sessionId</li>
 * </ul>
 *
 * <p>消费流程：
 * <pre>
 *   1. 消费者收到消息后，先 INSERT 一条去重记录
 *   2. INSERT 成功 → 首次消费，继续执行业务
 *   3. INSERT 失败（唯一键冲突）→ 已消费过，直接 ACK 跳过（幂等）
 * </pre>
 *
 * @author urban-script-reservation
 */
@Data
@TableName("mq_consume_log")
public class MqConsumeLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 消费者名称（如 OrderTimeoutConsumer / SessionCloseConsumer） */
    private String consumerName;

    /** 业务去重键（如 orderNo / sessionId） */
    private String bizKey;

    /** 消费时间 */
    private LocalDateTime createTime;
}
