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

    /** 上架状态: 0=下架 1=上架 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
