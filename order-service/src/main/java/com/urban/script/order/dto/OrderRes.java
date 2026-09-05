package com.urban.script.order.dto;

import com.urban.script.order.entity.OrderInfo;
import com.urban.script.order.feign.ShopClient;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 订单详情响应 DTO
 *
 * <p>包含订单基础字段 + 场次时间信息（从 SessionFeignRes 拷贝）+ 可选的店铺/剧本名称。
 * 组装时通过 {@link #fromEntity(OrderInfo)} 或 {@link #fromEntityWithSession(OrderInfo, ShopClient.SessionFeignRes)}
 * 工厂方法，避免到处手动 set。
 *
 * @author urban-script-reservation
 */
@Data
@Builder
public class OrderRes {

    // ========== 订单基础字段（来自 order_info 表） ==========

    private Long id;
    private String orderNo;
    private Long userId;
    private Long sessionId;
    private Long scriptId;
    private Long shopId;
    private Integer playerCnt;
    private BigDecimal amount;
    private Integer payMethod;

    /**
     * 订单状态：0=待支付 1=已支付 2=已取消 3=已完成
     */
    private Integer status;

    private String cancelReason;
    private LocalDateTime createTime;
    private LocalDateTime payTime;

    // ========== 场次展示字段（来自 session_info，下单时从 Feign 拷贝） ==========
    // 这些字段在 session_info 表中，但订单列表/详情展示时非常有用，
    // 避免前端还要单独调场次接口

    /** 场次日期（来自 SessionFeignRes） */
    private LocalDate sessionDate;
    /** 场次开始时间（来自 SessionFeignRes） */
    private LocalTime startTime;
    /** 场次结束时间（来自 SessionFeignRes） */
    private LocalTime endTime;

    // ========== 可选扩展（P0 暂留 null，后续 ShopClient 加 shopName/scriptName 再补） ==========

    /** 店铺名称（需额外 Feign 调 shop-service 获取，P0 先 null） */
    private String shopName;
    /** 剧本名称（需额外 Feign 调 shop-service 获取，P0 先 null） */
    private String scriptName;

    // ========================================================================
    // 工厂方法
    // ========================================================================

    /**
     * 基础版：只从 OrderInfo 实体拷贝（不带场次展示字段）
     */
    public static OrderRes fromEntity(OrderInfo o) {
        if (o == null) return null;
        return OrderRes.builder()
                .id(o.getId())
                .orderNo(o.getOrderNo())
                .userId(o.getUserId())
                .sessionId(o.getSessionId())
                .scriptId(o.getScriptId())
                .shopId(o.getShopId())
                .playerCnt(o.getPlayerCnt())
                .amount(o.getAmount())
                .payMethod(o.getPayMethod())
                .status(o.getStatus())
                .cancelReason(o.getCancelReason())
                .createTime(o.getCreateTime())
                .payTime(o.getPayTime())
                .build();
    }

    /**
     * 完整版：从 OrderInfo + SessionFeignRes 组装（带场次时间信息）
     *
     * @param o 订单实体
     * @param session Feign 获取的场次信息（可为 null，此时回退到 fromEntity）
     */
    public static OrderRes fromEntityWithSession(OrderInfo o, ShopClient.SessionFeignRes session) {
        if (o == null) return null;
        OrderResBuilder builder = OrderRes.builder()
                .id(o.getId())
                .orderNo(o.getOrderNo())
                .userId(o.getUserId())
                .sessionId(o.getSessionId())
                .scriptId(o.getScriptId())
                .shopId(o.getShopId())
                .playerCnt(o.getPlayerCnt())
                .amount(o.getAmount())
                .payMethod(o.getPayMethod())
                .status(o.getStatus())
                .cancelReason(o.getCancelReason())
                .createTime(o.getCreateTime())
                .payTime(o.getPayTime());

        // 场次信息从 Feign 结果拷贝
        if (session != null) {
            builder.sessionDate(session.getSessionDate())
                    .startTime(session.getStartTime())
                    .endTime(session.getEndTime());
        }

        return builder.build();
    }
}
