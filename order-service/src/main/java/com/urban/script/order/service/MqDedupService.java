package com.urban.script.order.service;

import com.urban.script.order.entity.MqConsumeLog;
import com.urban.script.order.mapper.MqConsumeLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * MQ 消息消费去重服务
 *
 * <p>核心逻辑：消费前先往 mq_consume_log 插入一条 (consumerName, bizKey) 记录，
 * 利用唯一索引 {@code uk_consumer_biz} 做幂等判断：
 * <ul>
 *   <li>INSERT 成功 → 首次消费，返回 true，消费者继续执行业务</li>
 *   <li>INSERT 抛 DuplicateKeyException → 该消息已被消费过，返回 false，消费者直接 ACK 跳过</li>
 * </ul>
 *
 * <p><b>为什么用 DB 唯一索引而不是 Redis SETNX？</b>
 * <ul>
 *   <li>DB 唯一索引是持久化的，消费者重启 / Redis 宕机后去重记录不丢失</li>
 *   <li>消费本身就是写 DB（改订单状态），多一次 INSERT 开销可接受</li>
 *   <li>消息幂等的三种实现思路：唯一键去重表 / Redis SETNX / 状态机判断，本项目选前者</li>
 * </ul>
 *
 * <p><b>与状态机幂等的关系：</b>
 * 本项目采用"双重幂等"——消息去重表（消息级）+ updateStatus WHERE status=0（业务级）。
 * 去重表挡同一条消息的重复投递；状态机挡不同消息对同一订单的并发处理（如超时关单 vs 场次关闭）。
 *
 * @author urban-script-reservation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MqDedupService {

    private final MqConsumeLogMapper mqConsumeLogMapper;

    /**
     * 尝试消费一条消息
     *
     * @param consumerName 消费者名称（用于区分不同消费者的去重空间）
     * @param bizKey       业务去重键（如 orderNo / sessionId）
     * @return true=首次消费，可执行业务；false=已消费过，应跳过
     */
    public boolean tryConsume(String consumerName, String bizKey) {
        MqConsumeLog logRecord = new MqConsumeLog();
        logRecord.setConsumerName(consumerName);
        logRecord.setBizKey(bizKey);

        try {
            mqConsumeLogMapper.insert(logRecord);
            return true;
        } catch (DuplicateKeyException e) {
            // 唯一键冲突 → 已消费过，幂等跳过
            log.info("[MqDedupService] 消息已消费过，幂等跳过 consumerName={}, bizKey={}",
                    consumerName, bizKey);
            return false;
        }
    }
}
