package com.urban.script.shop.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 场次实体 —— 映射 session_info 表
 *
 * @author urban-script-reservation
 */
@Data
@TableName("session_info")
public class SessionInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 剧本 ID */
    private Long scriptId;

    /** 店铺 ID */
    private Long shopId;

    /** DM 玩家 ID（可空，允许未指派 DM） */
    private Long dmId;

    /** 场次日期 */
    private LocalDate sessionDate;

    /** 开始时间 */
    private LocalTime startTime;

    /** 结束时间 */
    private LocalTime endTime;

    /** 容纳人数（默认 6） */
    private Integer capacity;

    /** 已预约人数 */
    private Integer booked;

    /** 场次状态: 0=已关闭 1=进行中 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
