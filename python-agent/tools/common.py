"""
tools/common.py —— httpx.Client 单例（带 X-Internal-Api-Key 拦截器）

所有工具模块共享同一个 client，避免重复创建连接池。
"""
from __future__ import annotations

import httpx
from loguru import logger

import config


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


def safe_get(url: str, **kwargs) -> dict:
    """
    安全 GET —— 统一异常处理

    :param url: 完整 URL（已拼接 base_url）
    :param kwargs: 透传给 httpx.Client.get（params / headers 等）
    :return: JSON 响应 dict；失败返回 {"error": "..."}
    """
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
