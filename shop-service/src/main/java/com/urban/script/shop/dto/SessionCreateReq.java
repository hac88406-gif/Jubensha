package com.urban.script.shop.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 创建场次请求 DTO
 *
 * @author urban-script-reservation
 */
@Data
public class SessionCreateReq {

    @NotNull(message = "剧本 ID 不能为空")
    private Long scriptId;

    @NotNull(message = "店铺 ID 不能为空")
    private Long shopId;

    /** DM 玩家 ID（可空） */
    private Long dmId;

    @NotNull(message = "场次日期不能为空")
    private LocalDate sessionDate;

    @NotNull(message = "开始时间不能为空")
    private LocalTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalTime endTime;

    /** 容纳人数，默认 6 */
    @Min(value = 1, message = "容纳人数最小为 1")
    private Integer capacity = 6;
}
