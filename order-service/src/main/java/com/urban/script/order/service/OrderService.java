package com.urban.script.order.service;

import com.urban.script.order.dto.OrderCreateReq;
import com.urban.script.order.dto.OrderRes;

import java.util.List;

/**
 * 订单服务接口
 *
 * @author urban-script-reservation
 */
public interface OrderService {

    /**
     * 创建预约订单
     *
     * @param userId 下单用户 ID
     * @param req    下单请求（sessionId + playerCnt）
     * @return 订单号（orderNo，雪花算法生成，对外唯一标识）
     */
    String createOrder(Long userId, OrderCreateReq req);

    /**
     * 取消订单（主动取消 / 超时关单 / 场次关闭触发）
     *
     * @param orderId     订单主键 ID
     * @param cancelReason 取消原因（USER_CANCEL / TIMEOUT / SESSION_CLOSED）
     * @param operator    操作人（userId / "SYSTEM" / "SHOP_OWNER:xxx"）
     */
    void cancelOrder(Long orderId, String cancelReason, String operator);

    /**
     * 订单详情（带场次时间信息）
     */
    OrderRes getOrderDetail(Long orderId);

    /**
     * 订单详情（带归属校验版）——游客/玩家仅能看自己的订单，店长可看任意订单
     *
     * @param orderId 订单主键 ID
     * @param userId  当前登录用户 ID（X-User-Id）
     * @param role    当前登录用户角色（X-User-Role）
     */
    OrderRes getOrderDetailForUser(Long orderId, Long userId, String role);

    /**
     * 取消订单（带归属校验版）——玩家仅能取消自己的订单，店长可取消任意订单
     *
     * @param orderId      订单主键 ID
     * @param userId       当前登录用户 ID（X-User-Id）
     * @param role         当前登录用户角色（X-User-Role）
     * @param cancelReason 取消原因（USER_CANCEL）
     * @param operator     操作人描述（用于日志审计）
     */
    void cancelOrderForUser(Long orderId, Long userId, String role,
                            String cancelReason, String operator);

    /**
     * 用户订单列表（按创建时间倒序，带场次时间信息）
     */
    List<OrderRes> listOrdersByUserId(Long userId);
}
