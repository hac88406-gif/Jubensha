package com.urban.script.shop.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 店铺/剧本店实体 —— 映射 shop_info 表
 *
 * @author urban-script-reservation
 */
@Data
@TableName("shop_info")
public class ShopInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 店铺名称 */
    private String name;

    /** 店铺地址 */
    private String address;

    /** 联系电话 */
    private String phone;

    /** 店长 user_id（关联 user_info.id） */
    private Long ownerId;

    /** 状态：0=关闭 1=营业中 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
