"""
tools/script_tool.py —— 剧本查询工具

供 LangGraph call_tool 节点调用：
  - SEARCH_SCRIPT       → 按类型/人数/店铺筛选剧本（/agent/internal/script/list）
  - PLOT_QA / CHARACTER_INTRO → 剧本详情（/agent/internal/script/{id}，含 background/characters）
  - SIMILAR_SCRIPT      → Neo4j 相似剧本（/agent/internal/recommend/similar/{scriptId}）
  - SMART_PICK          → 智能选本（/agent/internal/recommend/filter，Cypher 过滤）
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
    result = safe_get("/agent/internal/script/list", params=params)

    # ── 三级回退：精确筛选为空时自动放宽，让 AI 有东西可推荐 ──
    # 场景：用户要"店铺3的6人硬核本"，但库里硬核本只有 2~5 人、且店铺3名下无硬核本。
    # 直接返回空会让 LLM 只能说"没查到"（被用户骂一问三不知）。
    # 回退阶梯（每级都如实标注范围变化，note 给 LLM 转述）：
    #   ① 去掉人数 → 同店同类型（人数接近）
    #   ② 再去掉店铺 → 全平台同类型且符合人数
    #   ③ 只留类型 → 全平台同类型（人数最接近）
    if (
        isinstance(result, dict)
        and result.get("code") == 200
        and not result.get("data")
        and player_cnt is not None
    ):
        # ① 放宽人数：保留类型/店铺，去掉 playerCnt
        relaxed = dict(params)
        relaxed.pop("playerCnt", None)
        if relaxed:
            fb1 = safe_get("/agent/internal/script/list", params=relaxed)
            if (
                isinstance(fb1, dict)
                and fb1.get("code") == 200
                and fb1.get("data")
            ):
                logger.info(
                    f"[script_tool] search_scripts 精确为空，①放宽人数回退 → "
                    f"{len(fb1['data'])} 条"
                )
                result = {
                    "code": 200,
                    "data": _top_rated(fb1["data"]),
                    "fallback": True,
                    "fallback_level": 1,
                    "original_player_cnt": player_cnt,
                    "note": f"没有完全符合 {player_cnt} 人的，已放宽人数返回相近推荐",
                }
                return result

        # ② 换店铺：去掉 shopId，改查全平台且符合人数（保留 playerCnt 比①更精准）
        platform_cnt = dict(params)
        platform_cnt.pop("shopId", None)
        if shop_id and platform_cnt:
            fb2 = safe_get("/agent/internal/script/list", params=platform_cnt)
            if (
                isinstance(fb2, dict)
                and fb2.get("code") == 200
                and fb2.get("data")
            ):
                logger.info(
                    f"[script_tool] search_scripts 店铺{shop_id}下无该类型，"
                    f"②换店铺回退 → {len(fb2['data'])} 条"
                )
                result = {
                    "code": 200,
                    "data": _top_rated(fb2["data"]),
                    "fallback": True,
                    "fallback_level": 2,
                    "original_player_cnt": player_cnt,
                    "note": (
                        f"店铺 {shop_id} 下没有符合 {player_cnt} 人的"
                        f"{script_type or ''}本，已改为全平台检索"
                    ),
                }
                return result

        # ③ 只留类型：全平台同类型（去掉人数和店铺，人数取最接近的）
        type_only = {"type": script_type} if script_type else {}
        if type_only:
            fb3 = safe_get("/agent/internal/script/list", params=type_only)
            if (
                isinstance(fb3, dict)
                and fb3.get("code") == 200
                and fb3.get("data")
            ):
                logger.info(
                    f"[script_tool] search_scripts 全平台无 {player_cnt} 人的"
                    f"{script_type or ''}本，③只留类型回退 → {len(fb3['data'])} 条"
                )
                result = {
                    "code": 200,
                    "data": _top_rated(fb3["data"]),
                    "fallback": True,
                    "fallback_level": 3,
                    "original_player_cnt": player_cnt,
                    "note": (
                        f"全平台都没有正好 {player_cnt} 人的"
                        f"{script_type or ''}本，已改为推荐人数最接近的"
                    ),
                }
                return result
    return result


def _top_rated(data: list, limit: int = 10) -> list:
    """
    回退结果截取评分最高的前 limit 条（工具不负责排序，按 mark 降序取头部）。

    精确筛选可能命中 100+ 本，全部塞给 LLM 会超上下文且稀释重点；
    回退时只给"评分最高的一小撮"，让 AI 推荐更聚焦。
    """
    def _mark(s: dict) -> float:
        try:
            m = s.get("mark")
            return float(m) if m is not None else -1.0
        except (TypeError, ValueError):
            return -1.0

    return sorted(data, key=_mark, reverse=True)[:limit]


def resolve_script_id(script_name: str) -> int | None:
    """
    按书名解析剧本 ID（剧情问答 / 角色介绍 / 相似剧本工具的第一步）

    拉全量剧本列表（202 本量级），先精确匹配再包含匹配，
    找不到返回 None（由 call_tool 转成友好提示给 LLM）。
    """
    if not script_name:
        return None
    data = safe_get("/agent/internal/script/list")
    scripts = data.get("data") if isinstance(data, dict) else None
    if not isinstance(scripts, list):
        logger.warning(f"[script_tool] resolve_script_id 列表拉取失败: {data}")
        return None

    name = script_name.strip()
    # ① 精确匹配（去掉书名号后）
    for s in scripts:
        if str(s.get("name", "")).strip() == name:
            return s.get("id")
    # ② 包含匹配（如用户只输关键词）
    for s in scripts:
        if name in str(s.get("name", "")):
            return s.get("id")
    logger.info(f"[script_tool] resolve_script_id 未找到《{name}》")
    return None


def get_script_detail(script_id: int) -> dict:
    """
    剧本详情（含 background / characters / tags 等富化字段）

    :param script_id: 剧本 ID（先经 resolve_script_id 解析）
    :return:
        成功: {"code": 200, "data": {name, scriptType, background, characters, ...}}
        失败: {"error": "..."}
    """
    logger.info(f"[script_tool] get_script_detail scriptId={script_id}")
    return safe_get(f"/agent/internal/script/{script_id}")


def similar_scripts(script_id: int) -> dict:
    """
    Neo4j 关系查询 —— 和某本类似的剧本（RELATED_TO 关系召回）

    :param script_id: 剧本 ID
    :return:
        成功: {"code": 200, "data": [{scriptId, name, image, mark, scriptType, score}, ...]}
        失败: {"error": "..."}
    """
    logger.info(f"[script_tool] similar_scripts scriptId={script_id}")
    return safe_get(f"/agent/internal/recommend/similar/{script_id}")


def smart_pick(
    script_type: str | None = None,
    player_cnt: int | None = None,
    tag: str | None = None,
) -> dict:
    """
    智能选本 —— Neo4j Cypher 过滤（类型 / 人数 / 细标签组合筛选，评分降序）

    :param script_type: 剧本类型（情感 / 硬核 / 欢乐 / 机制 ...），可 None
    :param player_cnt: 期望人数，可 None
    :param tag: 细标签（推理 / 解谜 / 还原 ...），可 None
    :return:
        成功: {"code": 200, "data": [{scriptId, name, scriptType, playerMin, playerMax, mark, tags}, ...]}
        失败: {"error": "..."}
    """
    params: dict[str, object] = {}
    if script_type:
        params["type"] = script_type
    if player_cnt:
        params["playerCnt"] = player_cnt
    if tag:
        params["tag"] = tag
    logger.info(
        f"[script_tool] smart_pick type={script_type}, playerCnt={player_cnt}, tag={tag}"
    )
    result = safe_get("/agent/internal/recommend/filter", params=params)

    # ── 三级回退：精确筛选为空时自动放宽（与 search_scripts 一致）──
    #   ① 去掉人数 → 同类型同细标签
    #   ② 去掉细标签 → 同类型且符合人数
    #   ③ 只留类型 → 同类型（人数最接近）
    if (
        isinstance(result, dict)
        and result.get("code") == 200
        and not result.get("data")
        and player_cnt is not None
    ):
        # ① 放宽人数：保留类型/细标签
        relaxed = dict(params)
        relaxed.pop("playerCnt", None)
        if relaxed:
            fb1 = safe_get("/agent/internal/recommend/filter", params=relaxed)
            if (
                isinstance(fb1, dict)
                and fb1.get("code") == 200
                and fb1.get("data")
            ):
                logger.info(
                    f"[script_tool] smart_pick 精确为空，①放宽人数回退 → "
                    f"{len(fb1['data'])} 条"
                )
                result = {
                    "code": 200,
                    "data": _top_rated(fb1["data"]),
                    "fallback": True,
                    "fallback_level": 1,
                    "original_player_cnt": player_cnt,
                    "note": f"没有完全符合 {player_cnt} 人的，已放宽人数返回相近推荐",
                }
                return result

        # ② 去掉细标签：保留类型+人数（比①更接近用户预期）
        no_tag = dict(params)
        no_tag.pop("tag", None)
        if tag and no_tag:
            fb2 = safe_get("/agent/internal/recommend/filter", params=no_tag)
            if (
                isinstance(fb2, dict)
                and fb2.get("code") == 200
                and fb2.get("data")
            ):
                logger.info(
                    f"[script_tool] smart_pick 无 {player_cnt} 人的「{tag}」标签"
                    f"{script_type or ''}本，②去掉标签回退 → {len(fb2['data'])} 条"
                )
                result = {
                    "code": 200,
                    "data": _top_rated(fb2["data"]),
                    "fallback": True,
                    "fallback_level": 2,
                    "original_player_cnt": player_cnt,
                    "note": (
                        f"没有同时满足 {player_cnt} 人和「{tag}」标签的"
                        f"{script_type or ''}本，已去掉标签推荐相近的"
                    ),
                }
                return result

        # ③ 只留类型：全类型下人数最接近
        type_only = {"type": script_type} if script_type else {}
        if type_only:
            fb3 = safe_get("/agent/internal/recommend/filter", params=type_only)
            if (
                isinstance(fb3, dict)
                and fb3.get("code") == 200
                and fb3.get("data")
            ):
                logger.info(
                    f"[script_tool] smart_pick 全库无 {player_cnt} 人的"
                    f"{script_type or ''}本，③只留类型回退 → {len(fb3['data'])} 条"
                )
                result = {
                    "code": 200,
                    "data": _top_rated(fb3["data"]),
                    "fallback": True,
                    "fallback_level": 3,
                    "original_player_cnt": player_cnt,
                    "note": (
                        f"全库都没有正好 {player_cnt} 人的{script_type or ''}本，"
                        f"已改为推荐人数最接近的"
                    ),
                }
                return result
    return result
