package com.urban.script.shop.dto;

import com.urban.script.shop.entity.ScriptInfo;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 剧本响应 DTO
 *
 * @author urban-script-reservation
 */
@Data
@Builder
public class ScriptRes {

    private Long id;
    private Long shopId;
    private String name;
    private String author;
    private String scriptType;
    private Integer playerMin;
    private Integer playerMax;
    private Integer duration;
    private BigDecimal price;
    private Integer stock;
    private Integer status;
    private LocalDateTime createTime;

    public static ScriptRes fromEntity(ScriptInfo entity) {
        if (entity == null) return null;
        return ScriptRes.builder()
                .id(entity.getId())
                .shopId(entity.getShopId())
                .name(entity.getName())
                .author(entity.getAuthor())
                .scriptType(entity.getScriptType())
                .playerMin(entity.getPlayerMin())
                .playerMax(entity.getPlayerMax())
                .duration(entity.getDuration())
                .price(entity.getPrice())
                .stock(entity.getStock())
                .status(entity.getStatus())
                .createTime(entity.getCreateTime())
                .build();
    }
}
