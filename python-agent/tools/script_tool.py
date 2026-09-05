"""
tools/script_tool.py —— 剧本查询工具

供 LangGraph call_tool 节点调用：根据意图 SEARCH_SCRIPT
代理到 Java shop-service 的 /script/internal/list 接口。
"""
from __future__ import annotations

from loguru import logger

from tools.common import safe_get


def search_scripts(
    script_type: str | None = None,
    player_cnt: int | None = None,
    shop_id: int | None = None,
) -> dict:
    """
    按类型 / 人数 / 店铺筛选剧本

    :param script_type: 剧本类型（情感 / 硬核 / 欢乐 / 机制 ...），可 None
    :param player_cnt: 期望人数，可 None
    :param shop_id: 店铺 ID（支持"XX 店有什么本"的按店筛选场景），可 None
    :return:
        成功: {"code": 200, "data": [...]}
        失败: {"error": "..."}
    """
    params: dict[str, object] = {}
    if script_type:
        params["type"] = script_type
    if player_cnt:
        params["playerCnt"] = player_cnt
    if shop_id:
        params["shopId"] = shop_id

    logger.info(
        f"[script_tool] search_scripts type={script_type}, "
        f"playerCnt={player_cnt}, shopId={shop_id}"
    )
    return safe_get("/agent/internal/script/list", params=params)
