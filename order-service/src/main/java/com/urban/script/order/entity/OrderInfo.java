package com.urban.script.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体 —— 映射 order_info 表
 *
 * <p>与 MySQL 字段对齐（已确认）：
 * <pre>
 * id            BIGINT PK AUTO_INCREMENT
 * order_no      VARCHAR(64) UNIQUE NOT NULL
 * user_id       BIGINT NOT NULL
 * shop_id       BIGINT NOT NULL
 * script_id     BIGINT NULL
 * session_id    BIGINT NOT NULL
 * player_cnt    INT NOT NULL
 * play_time     DATETIME NOT NULL      ← 场次开场时间（下单时从 session 拷贝）
 * amount        DECIMAL(10,2) DEFAULT 0
 * pay_method    TINYINT DEFAULT 0      ← 0=未支付 1=微信 2=支付宝 3=线下 (P0 需 ALTER TABLE 加)
 * pay_time      DATETIME NULL          ← 支付成功时间
 * status        TINYINT NOT NULL DEFAULT 0
 * cancel_reason VARCHAR(20) NULL
 * create_time   DATETIME DEFAULT CURRENT_TIMESTAMP
 * update_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE
 * </pre>
 *
 * <p>订单状态机：
 * <pre>
 *   [0 待支付] ──支付成功──► [1 已支付] ──开场完成──► [3 已完成]
 *       │
 *       └── [2 已取消] ──(超时 / 主动 / 场次关闭触发)
 * </pre>
 *
 * @author urban-script-reservation
 */
@Data
@TableName("order_info")
public class OrderInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号（雪花算法生成，对外暴露） */
    private String orderNo;

    /** 玩家用户 ID */
    private Long userId;

    /** 店铺 ID */
    private Long shopId;

    /** 剧本 ID（冗余） */
    private Long scriptId;

    /** 场次 ID（跨服务关联，不做外键约束） */
    private Long sessionId;

    /** 预约玩家数 */
    private Integer playerCnt;

    /** 场次开场时间（下单时从 session 拷贝，避免场次关闭后丢失） */
    private LocalDateTime playTime;

    /** 订单总金额 */
    private BigDecimal amount;

    /** 支付方式：0=未支付 1=微信 2=支付宝 3=线下 */
    private Integer payMethod;

    /** 支付成功时间 */
    private LocalDateTime payTime;

    /**
     * 订单状态：
     * 0 = 待支付（已 Redis 扣库存，保留 15 分钟）
     * 1 = 已支付
     * 2 = 已取消（超时 / 主动 / 场次关闭）
     * 3 = 已完成
     */
    private Integer status;

    /** 取消原因（status=2 时有值） */
    private String cancelReason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
