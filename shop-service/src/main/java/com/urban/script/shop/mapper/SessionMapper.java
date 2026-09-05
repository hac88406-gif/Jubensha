package com.urban.script.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.urban.script.shop.entity.SessionInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 场次 Mapper
 *
 * @author urban-script-reservation
 */
@Mapper
public interface SessionMapper extends BaseMapper<SessionInfo> {

    /**
     * 检查 DM 在指定日期时间段内是否已有未关闭场次（冲突检测）。
     *
     * <p>冲突判定：已有场次的时间区间与 [start, end) 有交集 —— 即 NOT (已有.end <= 请求.start OR 已有.start >= 请求.end)。
     * 仅统计 status=1（未关闭）的场次。
     *
     * @param dmId  DM 玩家 ID
     * @param date  场次日期
     * @param start 开始时间
     * @param end   结束时间
     * @return 冲突场次数量，>0 表示冲突
     */
    @Select("SELECT COUNT(*) FROM session_info " +
            "WHERE dm_id = #{dmId} " +
            "  AND session_date = #{date} " +
            "  AND status = 1 " +
            "  AND NOT (end_time <= #{start} OR start_time >= #{end})")
    int countDmConflict(@Param("dmId") Long dmId,
                        @Param("date") LocalDate date,
                        @Param("start") LocalTime start,
                        @Param("end") LocalTime end);

    /**
     * 统计某场次的"未支付订单"总玩家数。
     *
     * <p>用于场次关闭时，决定 RabbitMQ 消息中 playerCntTotal 字段。
     * 注意：shop-service 与 order-service 共用同一个库，此处直接查 order_info 表。
     * 后续若拆分为独立库，应改为 Feign 调用 order-service。
     *
     * @param sessionId 场次 ID
     * @return 未支付订单累计玩家数；无记录时返回 0
     */
    @Select("SELECT COALESCE(SUM(player_cnt), 0) FROM order_info " +
            "WHERE session_id = #{sessionId} AND status = 0")
    int sumPendingPlayerCnt(@Param("sessionId") Long sessionId);
}
