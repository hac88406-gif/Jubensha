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

    /**
     * 查询某用户在某场次是否有未取消订单（status IN (0,1,3)：待支付/已支付/已完成）。
     * <p>
     * 用于业务层唯一性校验：一场次一人只能下一单，Redisson 锁只防并发提交，
     * 这个 DB 查询防锁释放后再次下单。
     */
    @Select("SELECT * FROM order_info WHERE user_id = #{userId} AND session_id = #{sessionId} " +
            "AND status IN (0, 1, 3) LIMIT 1")
    OrderInfo selectActiveByUserAndSession(@Param("userId") Long userId,
                                           @Param("sessionId") Long sessionId);

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

    /**
     * 原子支付成功 —— 带幂等保护（只有 status=0 待支付才能改掉）
     *
     * <p>支付回调专用：UPDATE ... WHERE status = 0 保证并发回调 / 重复回调
     * 只有一个能成功，另一个返回 0 行幂等跳过。
     *
     * @param orderNo 订单号
     * @return 更新行数：1=本次回调生效；0=已被其他路径处理（已支付/已取消）
     */
    @Update("UPDATE order_info SET status = 1, pay_time = NOW(), update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND status = 0")
    int updateStatusToPaid(@Param("orderNo") String orderNo);

    /**
     * 原子取消订单 —— 带幂等 + 防误伤保护（只有 status=0 待支付才能被取消）
     *
     * <p>与 {@link #updateStatusToPaid} 对称：支付(0→1) 和取消(0→2) 都是
     * {@code WHERE status = 0} 原子流转，两个并发路径只有一个能成功：
     * <ul>
     *   <li>并发取消 × 2 → 只有一个 UPDATE 返回 1，另一个返回 0 幂等跳过，
     *       杜绝 booked 被双重回滚（库存虚增）</li>
     *   <li>取消 vs 支付回调 → 若支付先成功(0→1)，本 UPDATE 返回 0 行，
     *       不会把已支付订单误改成已取消（资金事故）</li>
     * </ul>
     *
     * @param orderNo 订单号
     * @param reason  取消原因（USER_CANCEL / TIMEOUT / SESSION_CLOSED）
     * @return 更新行数：1=本次取消生效；0=已被其他路径处理（已支付/已取消/已完成）
     */
    @Update("UPDATE order_info SET status = 2, cancel_reason = #{reason}, update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND status = 0")
    int updateStatusToCancel(@Param("orderNo") String orderNo,
                             @Param("reason") String reason);
}
