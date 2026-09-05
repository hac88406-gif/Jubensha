package com.urban.script.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.urban.script.order.entity.OrderInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 订单 Mapper
 *
 * @author urban-script-reservation
 */
@Mapper
public interface OrderMapper extends BaseMapper<OrderInfo> {

    // ========================================================================
    // 查询方法
    // ========================================================================

    /**
     * 根据 orderNo 查订单（幂等保护用）
     */
    @Select("SELECT * FROM order_info WHERE order_no = #{orderNo} LIMIT 1")
    OrderInfo selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 查询用户订单列表（按创建时间倒序）
     */
    @Select("SELECT * FROM order_info WHERE user_id = #{userId} ORDER BY create_time DESC")
    List<OrderInfo> selectByUserId(@Param("userId") Long userId);

    /**
     * 查询某场次所有待支付订单（场次关闭时批量取消用）
     *
     * @param sessionId 场次 ID
     * @return status=0（待支付）的订单列表
     */
    @Select("SELECT * FROM order_info WHERE session_id = #{sessionId} AND status = 0")
    List<OrderInfo> selectPendingBySession(@Param("sessionId") Long sessionId);

    // ========================================================================
    // 原子更新方法（供 RabbitMQ Consumer 幂等关单用）
    // ========================================================================

    /**
     * 原子更新订单状态 + 取消原因 —— 带幂等保护（只有 status=0 才能被改掉）
     *
     * <p>WHERE status = 0 是关键：已经被其他消费者（如另一个超时关单实例 / 手动取消）
     * 处理过的订单 status 已经不是 0，UPDATE 返回 0 行，天然幂等。
     *
     * @param orderNo 订单号（唯一标识）
     * @param status  目标状态（固定 2=已取消）
     * @param reason  取消原因（TIMEOUT / SESSION_CLOSED / USER_CANCEL）
     * @return 更新行数：1=成功；0=已经被处理过（幂等跳过）
     */
    @Update("UPDATE order_info SET status = #{status}, cancel_reason = #{reason}, update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND status = 0")
    int updateStatusAndCancelReason(@Param("orderNo") String orderNo,
                                    @Param("status") int status,
                                    @Param("reason") String reason);
}
