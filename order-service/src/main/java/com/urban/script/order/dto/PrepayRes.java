package com.urban.script.order.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 发起支付响应 DTO（prepay 返回值）
 *
 * <p>模拟支付平台返回的「拉起支付参数」：前端/测试拿到 paymentNo 与 sign 后，
 * 模拟支付完成并携带 sign 调用 notify 回调接口完成入账。
 *
 * @author urban-script-reservation
 */
@Data
@Builder
public class PrepayRes {

    /** 支付流水号（幂等键，notify 回调时回传） */
    private String paymentNo;

    /** 关联订单号 */
    private String orderNo;

    /** 支付金额 */
    private BigDecimal amount;

    /** 支付方式 */
    private Integer payMethod;

    /** 支付渠道（演示用 SIM=模拟渠道） */
    private String channel;

    /** 模拟签名（HMAC-SHA256，notify 回调验签用） */
    private String sign;

    /** 模拟收银台跳转链接（演示用，真实支付平台会返回二维码/收银台地址） */
    private String payUrl;
}