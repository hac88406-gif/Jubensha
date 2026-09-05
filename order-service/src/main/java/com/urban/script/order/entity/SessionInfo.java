package com.urban.script.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
 * <p><b>注意</b>：order-service 内部专用，仅给 {@link com.urban.script.order.service.StockService}
 * 做 Redis 库存懒加载时查 MySQL 用。业务层通过
 * {@link com.urban.script.order.feign.ShopClient} Feign 调 shop-service 获取场次信息，
 * 不直接查 session_info 表。
 *
 * <p>字段与 shop-service 的 {@code entity.SessionInfo} 完全对齐。
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

    /** DM 玩家 ID（可空） */
    private Long dmId;

    /** 场次日期 */
    private LocalDate sessionDate;

    /** 开始时间 */
    private LocalTime startTime;

    /** 结束时间 */
    private LocalTime endTime;

    /** 容纳人数 */
    private Integer capacity;

    /** 已预约人数 */
    private Integer booked;

    /** 场次状态：0=已关闭 1=开放 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
