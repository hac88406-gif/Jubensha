package com.urban.script.shop.dto;

import com.urban.script.shop.entity.ScriptInfo;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    // ============ 富化字段 ============
    private String image;
    private String background;
    private String tags;
    private BigDecimal mark;
    private Integer markCnt;
    private Integer maleNum;
    private Integer femaleNum;
    private Integer unknownNum;

    /**
     * 角色列表（结构化数组，仅详情接口返回；列表接口由 Service 剥离保持为 null，
     * 避免全量列表 payload 过大）
     */
    private List<Map<String, Object>> characters;

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
                .image(entity.getImage())
                .background(entity.getBackground())
                .tags(entity.getTags())
                .mark(entity.getMark())
                .markCnt(entity.getMarkCnt())
                .maleNum(entity.getMaleNum())
                .femaleNum(entity.getFemaleNum())
                .unknownNum(entity.getUnknownNum())
                .build();
    }
}
