package com.urban.script.shop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建剧本请求 DTO
 *
 * @author urban-script-reservation
 */
@Data
public class ScriptCreateReq {

    @NotNull(message = "所属店铺不能为空")
    private Long shopId;

    @NotBlank(message = "剧本名称不能为空")
    @Size(max = 100, message = "剧本名称最长 100")
    private String name;

    @Size(max = 50, message = "作者最长 50")
    private String author;

    /** 类型: 硬核/情感/欢乐/机制 */
    @Size(max = 20)
    private String scriptType;

    private Integer playerMin = 4;
    private Integer playerMax = 8;
    private Integer duration = 120;
    private BigDecimal price = BigDecimal.ZERO;
    private Integer stock = 0;
}
