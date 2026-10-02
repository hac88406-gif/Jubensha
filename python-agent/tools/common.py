"""
tools/common.py —— httpx.Client 单例（带 X-Internal-Api-Key + X-Trace-Id 拦截）

所有工具模块共享同一个 client，避免重复创建连接池。
出站 header 每次调用动态合并：内部鉴权 Key（静态）+ 当前请求 traceId（动态，跨语言链路闭环）。
"""
from __future__ import annotations

import httpx
from loguru import logger

import config
from trace_id import TRACE_ID_HEADER, get_trace_id


# ==========================================================================
# 单例 httpx.Client —— 所有对 Java agent-gateway 的 HTTP 调用都走这个
# ==========================================================================

def _make_client() -> httpx.Client:
    """创建带默认 Header 的 httpx.Client（同步，LangGraph 用同步版本）"""
    return httpx.Client(
        base_url=config.JAVA_BASE_URL,
        headers={
            "X-Internal-Api-Key": config.INTERNAL_API_KEY,
            "Content-Type": "application/json",
        },
        timeout=httpx.Timeout(10.0, connect=5.0),
    )


# 进程级单例（import 一次就好）
http_client: httpx.Client = _make_client()
"""全工具模块共享的 httpx.Client"""


def _merge_trace_header(headers: dict | None = None) -> dict:
    """
    合并调用方自定义 header 与当前请求的 traceId（回传 Java 侧，形成跨语言链路闭环）

    规则：
      - 无 traceId（脚本直连 / 后台任务等非请求上下文）时不加头，保持原有行为；
      - 调用方显式传了同名 header 时以调用方为准，不覆盖。

    :param headers: 调用方自定义 header（如 X-User-Id），可为 None
    :return: 合并后的新 dict（不修改入参）
    """
    merged: dict = dict(headers or {})
    trace_id = get_trace_id()
    if trace_id and TRACE_ID_HEADER not in merged:
        merged[TRACE_ID_HEADER] = trace_id
    return merged


def safe_get(url: str, **kwargs) -> dict:
    """
    安全 GET —— 统一异常处理

    :param url: 完整 URL（已拼接 base_url）
    :param kwargs: 透传给 httpx.Client.get（params / headers 等）
    :return: JSON 响应 dict；失败返回 {"error": "..."}
    """
    # 回传 traceId：Python → agent-gateway 的内部调用也落在同一条链路上
    kwargs["headers"] = _merge_trace_header(kwargs.get("headers"))
    try:
        resp = http_client.get(url, **kwargs)
        if resp.status_code == 200:
            return resp.json()
        else:
            logger.warning(f"GET {url} -> HTTP {resp.status_code}: {resp.text[:200]}")
            return {"error": f"HTTP {resp.status_code}", "raw": resp.text[:200]}
    except httpx.RequestError as e:
        logger.error(f"GET {url} 网络异常: {e}")
        return {"error": f"网络异常: {e}"}


def safe_post(url: str, json: dict | None = None, **kwargs) -> dict:
    """安全 POST"""
    kwargs["headers"] = _merge_trace_header(kwargs.get("headers"))
    try:
        resp = http_client.post(url, json=json, **kwargs)
        if resp.status_code == 200:
            return resp.json()
        else:
            logger.warning(f"POST {url} -> HTTP {resp.status_code}: {resp.text[:200]}")
            return {"error": f"HTTP {resp.status_code}", "raw": resp.text[:200]}
    except httpx.RequestError as e:
        logger.error(f"POST {url} 网络异常: {e}")
        return {"error": f"网络异常: {e}"}
