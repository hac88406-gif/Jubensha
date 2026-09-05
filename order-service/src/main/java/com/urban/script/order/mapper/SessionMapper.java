package com.urban.script.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.urban.script.order.entity.SessionInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * session_info 表 Mapper —— 仅给 StockService 懒加载 + Redis 降级 MySQL 乐观锁用
 *
 * <p><b>业务层禁止直接使用此 Mapper</b>，场次信息统一通过
 * {@link com.urban.script.order.feign.ShopClient} Feign 获取。
 *
 * @author urban-script-reservation
 */
@Mapper
public interface SessionMapper extends BaseMapper<SessionInfo> {

    // ========================================================================
    // Redis 降级 MySQL 乐观锁 —— 扣减已预约数
    // ========================================================================

    /**
     * MySQL 乐观锁扣减 booked（带条件：status=1 且 booked+playerCnt ≤ capacity）
     *
     * <p>用于 Redis 不可用时的降级路径。WHERE 子句同时满足：
     * <ul>
     *   <li>场次必须开放（status=1）</li>
     *   <li>扣减后不超过容量（booked + playerCnt ≤ capacity）—— 这就是乐观锁条件</li>
     * </ul>
     *
     * @return 更新行数：1=成功；0=场次已满/已关闭（竞争失败）
     */
    @Update("UPDATE session_info SET booked = booked + #{playerCnt} " +
            "WHERE id = #{sessionId} " +
            "AND status = 1 " +
            "AND booked + #{playerCnt} <= capacity")
    int incrementBookedIfEnough(@Param("sessionId") Long sessionId,
                                @Param("playerCnt") int playerCnt);

    /**
     * 简单增加 booked（Redis+Lua 成功后，MySQL 也同步一下，保持最终一致）
     *
     * <p>这里不带 capacity 条件 —— Redis 原子扣减已经保证不超卖，MySQL 只是最终同步。
     */
    @Update("UPDATE session_info SET booked = booked + #{playerCnt} WHERE id = #{sessionId}")
    int incrementBooked(@Param("sessionId") Long sessionId,
                        @Param("playerCnt") int playerCnt);

    // ========================================================================
    // 取消订单时回滚 MySQL booked
    // ========================================================================

    /**
     * MySQL 回滚 booked（取消订单时调用）
     *
     * @return 更新行数：1=成功；0=场次不存在
     */
    @Update("UPDATE session_info SET booked = booked - #{playerCnt} WHERE id = #{sessionId} AND booked >= #{playerCnt}")
    int decrementBookedIfEnough(@Param("sessionId") Long sessionId,
                                @Param("playerCnt") int playerCnt);
}
