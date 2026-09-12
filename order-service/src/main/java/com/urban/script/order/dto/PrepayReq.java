package com.urban.script.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发起支付请求 DTO（prepay）
 *
 * @author urban-script-reservation
 */
@Data
public class PrepayReq {

    /** 订单号（待支付状态的订单） */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /** 支付方式：1-微信 2-支付宝 3-线下 */
    @NotNull(message = "支付方式不能为空")
    @Min(value = 1, message = "支付方式必须在 1~3 之间")
    @Max(value = 3, message = "支付方式必须在 1~3 之间")
    private Integer payMethod;
}