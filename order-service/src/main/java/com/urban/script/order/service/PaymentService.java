package com.urban.script.order.service;

import com.urban.script.order.dto.PayNotifyReq;
import com.urban.script.order.dto.PrepayReq;
import com.urban.script.order.dto.PrepayRes;

/**
 * 支付服务 —— 模拟支付闭环
 *
 * <p>玩家下单（待支付）→ prepay 生成支付流水（status=0）→ 模拟支付平台回调
 * notify（验签 + 幂等 + 原子状态流转）→ 订单置为已支付。
 *
 * @author urban-script-reservation
 */
public interface PaymentService {

    /**
     * 发起支付（prepay）
     *
     * @param userId 玩家 ID（X-User-Id）
     * @param req    订单号 + 支付方式
     * @return 模拟支付参数（paymentNo + sign + payUrl）
     */
    PrepayRes prepay(Long userId, PrepayReq req);

    /**
     * 模拟支付平台回调（notify）
     *
     * <p>验签通过后按「订单 → 流水」顺序原子置为已支付，重复回调幂等跳过。
     */
    void notify(PayNotifyReq req);
}