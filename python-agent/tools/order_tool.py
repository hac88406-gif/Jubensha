"""
tools/order_tool.py —— 订单查询 / 取消工具

供 LangGraph call_tool 节点调用：根据意图 QUERY_ORDER / QUERY_MY_ORDERS / CANCEL_ORDER
代理到 Java order-service 的 /order/internal/orderNo/{orderNo} / /order/internal/my / /order/internal/cancel
"""
from __future__ import annotations

from loguru import logger

from tools.common import safe_get, safe_post


def query_order(order_no: str, user_id: int | None = None) -> dict:
    """
    按 orderNo 查单个订单

    :param order_no: 订单号（如 ORD20260903...）
    :param user_id: 用户 ID（会加到请求头 X-User-Id，下游据此做"只能查本人订单"归属校验）
    :return:
        成功: {"code": 200, "data": {...}}
        失败: {"error": "..."}
    """
    logger.info(f"[order_tool] query_order orderNo={order_no}, userId={user_id}")
    headers = {"X-User-Id": str(user_id)} if user_id else None
    return safe_get(f"/agent/internal/order/{order_no}", headers=headers)


def list_my_orders(user_id: int) -> dict:
    """
    查当前用户所有订单

    :param user_id: 用户 ID（会加到请求头 X-User-Id）
    :return:
        成功: {"code": 200, "data": [...]}
        失败: {"error": "..."}
    """
    logger.info(f"[order_tool] list_my_orders userId={user_id}")
    # 注意：X-User-Id 需要在 Header 里传递，safe_get 默认只加 X-Internal-Api-Key
    # 这里追加一个自定义 header
    return safe_get(
        "/agent/internal/order/my",
        headers={"X-User-Id": str(user_id)},
    )


def cancel_order(order_no: str, user_id: int | None = None) -> dict:
    """
    按 orderNo 取消订单（AI 工具）

    流程：① POST 取消接口（agent-gateway → order-service /order/internal/cancel）
          ② 取消成功后回查订单，拿最终状态（幂等：已取消/已完成/开场前2小时内会直接跳过并返回当前状态）

    :param order_no: 订单号（ORD...）
    :param user_id: 用户 ID（X-User-Id 归属校验，只能取消本人订单）
    :return:
        成功: {"code": 200, "data": {最终订单状态}} —— 含 orderNo/status/cancelReason
        失败: {"error": "..."} 或 {"code": 非200, "message": "..."}（业务拒绝，如开场前2小时内）
    """
    logger.info(f"[order_tool] cancel_order orderNo={order_no}, userId={user_id}")
    if not order_no:
        return {"error": "订单号为空，无法取消"}

    headers = {"X-User-Id": str(user_id)} if user_id else None
    cancel_resp = safe_post(
        f"/agent/internal/order/{order_no}/cancel",
        headers=headers,
    )

    # 网络 / HTTP 异常
    if "error" in cancel_resp:
        return cancel_resp
    # 业务拒绝（code != 200）：如开场前2小时内不允许取消、订单不存在 —— 直接透传
    if cancel_resp.get("code") not in (None, 200):
        logger.info(f"[order_tool] cancel_order 业务拒绝: {cancel_resp}")
        return cancel_resp

    # 取消成功（R.ok("取消成功", null) → data=null），回查最终状态
    logger.info(f"[order_tool] cancel_order 成功，回查最终状态 orderNo={order_no}")
    return query_order(order_no, user_id)
