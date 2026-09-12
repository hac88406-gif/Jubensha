package com.urban.script.shop.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 剧本实体 —— 映射 script_info 表
 *
 * @author urban-script-reservation
 */
@Data
@TableName("script_info")
public class ScriptInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属店铺 */
    private Long shopId;

    /** 剧本名称 */
    private String name;

    /** 作者 */
    private String author;

    /** 类型: 硬核/情感/欢乐/机制 */
    private String scriptType;

    /** 最少人数 */
    private Integer playerMin;

    /** 最多人数 */
    private Integer playerMax;

    /** 时长（分钟） */
    private Integer duration;

    /** 单价 */
    private BigDecimal price;

    /** 可预约场次库存 */
    private Integer stock;

    /** 封面图 URL */
    private String image;

    /** 剧情简介 */
    private String background;

    /** 细标签，逗号分隔（恐怖/古风/本格...） */
    private String tags;

    /** 评分（0~10） */
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
     * 角色列表（JSON 数组字符串，如
     * [{"name":"仪伊","gender":2,"age":26,"desc":"...","image":"..."}]
     * gender: 1=男 2=女；由 Service 层做 JSON 序列化/反序列化
     */
    private String characters;

    /** 上架状态: 0=下架 1=上架 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
