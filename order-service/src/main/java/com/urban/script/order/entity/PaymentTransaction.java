package com.urban.script.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付流水实体 —— 映射 payment_transaction 表
 *
 * <p>支付闭环核心：玩家 prepay 生成流水（status=0 待支付）→ 模拟支付平台 notify 回调
 * 验签成功后流水与订单先后置为「已支付」。{@code paymentNo} 是业务幂等键，
 * 回调重复投递靠唯一键 + 状态机原子更新（WHERE status=0）兜底，天然防重复入账。
 *
 * <pre>
 * id           BIGINT PK AUTO_INCREMENT
 * payment_no   VARCHAR(64) UNIQUE NOT NULL   ← 业务幂等键（雪花生成）
 * order_no     VARCHAR(64) NOT NULL
 * user_id      BIGINT NOT NULL
 * amount       DECIMAL(10,2) NOT NULL
 * pay_method   TINYINT DEFAULT 1             ← 1-微信 2-支付宝 3-线下
 * status       TINYINT DEFAULT 0             ← 0-待支付 1-支付成功 2-支付失败
 * channel      VARCHAR(32) DEFAULT 'SIM'     ← 模拟渠道标识
 * pay_time     DATETIME NULL                 ← 支付成功时间
 * notify_time  DATETIME NULL                 ← 回调到达时间
 * create_time  / update_time
 * </pre>
 *
 * @author urban-script-reservation
 */
@Data
@TableName("payment_transaction")
public class PaymentTransaction implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 流水状态：待支付 */
    public static final int STATUS_PENDING = 0;
    /** 流水状态：支付成功 */
    public static final int STATUS_SUCCESS = 1;
    /** 流水状态：支付失败 */
    public static final int STATUS_FAILED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 支付流水号（雪花生成，对外暴露，业务幂等键） */
    private String paymentNo;

    /** 关联订单号 */
    private String orderNo;

    /** 付款用户 ID */
    private Long userId;

    /** 支付金额 */
    private BigDecimal amount;

    /** 支付方式：1-微信 2-支付宝 3-线下 */
    private Integer payMethod;

    /** 流水状态：0-待支付 1-支付成功 2-支付失败 */
    private Integer status;

    /** 支付渠道（演示用 SIM=模拟渠道） */
    private String channel;

    /** 支付成功时间 */
    private LocalDateTime payTime;

    /** 回调到达时间 */
    private LocalDateTime notifyTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}