package com.urban.script.recommend.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 剧本同步请求 DTO（内部接口）
 */
@Data
public class ScriptSyncReq {
    private Long scriptId;
    private String name;
    private String scriptType;
    private Integer playerMin;
    private Integer playerMax;
    private BigDecimal price;
    private BigDecimal mark;
    private String image;
    private String author;
    private List<String> tags;

    /**
     * 角色列表（可选，如 [{"name":"仪伊","gender":2,"age":26,"desc":"记者","image":"..."}]）
     * 注意: gender 语义 1=男 2=女；不传则图谱中不建 Character 节点
     */
    private List<Map<String, Object>> characters;
}
