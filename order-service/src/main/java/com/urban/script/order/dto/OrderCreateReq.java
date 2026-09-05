package com.urban.script.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建订单请求 DTO
 *
 * @author urban-script-reservation
 */
@Data
public class OrderCreateReq {

    @NotNull(message = "场次 ID 不能为空")
    private Long sessionId;

    /** 预约玩家数，默认 1 */
    @Min(value = 1, message = "至少 1 人")
    private Integer playerCnt = 1;
}
