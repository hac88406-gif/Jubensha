"""
tools/order_tool.py —— 订单查询工具

供 LangGraph call_tool 节点调用：根据意图 QUERY_ORDER / QUERY_MY_ORDERS
代理到 Java order-service 的 /order/internal/orderNo/{orderNo} / /order/internal/my
"""
from __future__ import annotations

from loguru import logger

from tools.common import safe_get


def query_order(order_no: str, user_id: int | None = None) -> dict:
    """
    按 orderNo 查单个订单

    :param order_no: 订单号（如 ORD20260903...）
    :param user_id: 可选（日志用）
    :return:
        成功: {"code": 200, "data": {...}}
        失败: {"error": "..."}
    """
    logger.info(f"[order_tool] query_order orderNo={order_no}, userId={user_id}")
    return safe_get(f"/agent/internal/order/{order_no}")


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
