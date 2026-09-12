package com.urban.script.shop.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 场次 Feign 内部接口响应 DTO
 *
 * <p>提供给 order-service 通过 /internal/session/{id} 拉取场次核心信息。
 * 字段精简（仅订单服务关心的字段），避免暴露 shop-service 内部细节。
 *
 * @author urban-script-reservation
 */
@Data
@Builder
public class SessionFeignRes {

    private Long id;
    private Long scriptId;
    private Long shopId;
    private Long dmId;
    private LocalDate sessionDate;
    private LocalTime startTime;
    private LocalTime endTime;
    /** 总容量 */
    private Integer capacity;
    /** 已预约 */
    private Integer booked;
    /** 剧本单价（供订单服务计算支付金额） */
    private BigDecimal price;
    private Integer status;

    /** 场次是否开放（status=1） */
    public boolean isOpen() {
        return status != null && status == 1;
    }
}
