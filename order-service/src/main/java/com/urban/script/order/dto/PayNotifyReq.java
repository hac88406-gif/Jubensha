package com.urban.script.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 模拟支付平台回调请求 DTO（notify）
 *
 * <p>真实支付平台（微信/支付宝）会在用户付款后异步回调商户系统，这里用同样的
 * 报文 + HMAC 签名模拟该过程，演示「回调验签 + 幂等 + 防重复入账」。
 *
 * @author urban-script-reservation
 */
@Data
public class PayNotifyReq {

    /** 支付流水号（prepay 返回） */
    @NotBlank(message = "paymentNo 不能为空")
    private String paymentNo;

    /** 订单号 */
    @NotBlank(message = "orderNo 不能为空")
    private String orderNo;

    /** 支付金额（必须与预支付流水一致） */
    @NotBlank(message = "amount 不能为空")
    private String amount;

    /** 支付渠道（SIM） */
    @NotBlank(message = "channel 不能为空")
    private String channel;

    /** 签名：HMAC-SHA256(secret, paymentNo=..&orderNo=..&amount=..&channel=..) */
    @NotBlank(message = "sign 不能为空")
    private String sign;

    public BigDecimal decimalAmount() {
        return new BigDecimal(amount);
    }
}