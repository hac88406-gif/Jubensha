package com.urban.script.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.urban.script.order.entity.MqConsumeLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * MQ 消费去重日志 Mapper
 *
 * <p>仅依赖 BaseMapper.insert()：
 * 插入成功 → 首次消费；唯一键冲突抛 DuplicateKeyException → 已消费过。
 *
 * @author urban-script-reservation
 */
@Mapper
public interface MqConsumeLogMapper extends BaseMapper<MqConsumeLog> {
}
