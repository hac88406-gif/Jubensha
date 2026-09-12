package com.urban.script.shop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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

    // ============ 富化字段（可选，爬虫/管理端可填）============
    /** 封面图 URL */
    @Size(max = 500)
    private String image;

    /** 剧情简介 */
    private String background;

    /** 细标签，逗号分隔 */
    @Size(max = 500)
    private String tags;

    /** 评分 0~10 */
    private BigDecimal mark;

    /** 评分人数 */
    private Integer markCnt;

    /** 男性角色数 */
    private Integer maleNum;

    /** 女性角色数 */
    private Integer femaleNum;

    /** 未知性别角色数 */
    private Integer unknownNum;

    /**
     * 角色列表（可选，如
     * [{"name":"仪伊","gender":2,"age":26,"desc":"记者","image":"..."}]
     * gender: 1=男 2=女）
     */
    private List<Map<String, Object>> characters;
}
