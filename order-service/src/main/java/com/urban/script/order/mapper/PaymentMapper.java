package com.urban.script.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.urban.script.order.entity.PaymentTransaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 支付流水 Mapper
 *
 * @author urban-script-reservation
 */
@Mapper
public interface PaymentMapper extends BaseMapper<PaymentTransaction> {

    /**
     * 按订单号查最近一条待支付流水（prepay 防重复生成）
     */
    @Select("SELECT * FROM payment_transaction WHERE order_no = #{orderNo} " +
            "AND status = 0 ORDER BY id DESC LIMIT 1")
    PaymentTransaction selectPendingByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 按流水号查询
     */
    @Select("SELECT * FROM payment_transaction WHERE payment_no = #{paymentNo} LIMIT 1")
    PaymentTransaction selectByPaymentNo(@Param("paymentNo") String paymentNo);

    /**
     * 原子置流水为支付成功 —— 带幂等保护（只有 status=0 待支付才能改掉）
     *
     * <p>回调重复投递时第一次成功（status=1），后续调用返回 0 行 → 幂等跳过，
     * 不会重复入账。记录 notify_time 用于排障。
     *
     * @param paymentNo 支付流水号
     * @return 更新行数：1=本次回调生效；0=已被处理过（幂等）
     */
    @Update("UPDATE payment_transaction SET status = 1, pay_time = NOW(), notify_time = NOW() " +
            "WHERE payment_no = #{paymentNo} AND status = 0")
    int tryMarkPaid(@Param("paymentNo") String paymentNo);
}