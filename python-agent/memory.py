"""
memory.py —— Redis 会话记忆

设计：
- Key 格式: agent:session:{userId}（Redis List，存 JSON 字符串）
- 操作:    LRANGE 读 / RPUSH 写 / LTRIM 裁剪
- 摘要压缩: 超过 MAX_HISTORY_ROUNDS（默认 10 轮）时，
            把最早一轮 + 最后一轮之外的消息打包送给 LLM 生成"历史摘要"，
            然后覆盖 Redis key，只保留"摘要 + 最近 N 轮"
- Redis 不可用时的优雅降级: 返回空列表，不阻塞主链路
"""
from __future__ import annotations

import json
from typing import Optional

from loguru import logger

import config

# Redis 客户端（lazy init，避免 import 时 Redis 还没启动）
_redis_client: Optional["redis.Redis"] = None


def _get_redis():
    """Lazy-load redis.Redis（import 失败时返回 None，自动降级）"""
    global _redis_client
    if _redis_client is not None:
        return _redis_client

    try:
        import redis
        from urllib.parse import urlparse

        # redis-py 5.x 支持直接传 URL
        _redis_client = redis.from_url(config.REDIS_URL, decode_responses=True)
        # 测试一下连通性
        _redis_client.ping()
        logger.info(f"[memory] ✅ Redis 连接成功: {config.REDIS_URL}")
        return _redis_client
    except Exception as e:
        logger.warning(f"[memory] ⚠️ Redis 不可用，会话记忆降级为内存: {e}")
        return None


# ==========================================================================
# Key 构造
# ==========================================================================

def _session_key(user_id: int) -> str:
    return f"agent:session:{user_id}"


# ==========================================================================
# 核心 API
# ==========================================================================

def get_history(user_id: int) -> list[dict]:
    """
    读取用户会话历史

    :return: 消息列表 [{"role": "user"/"assistant", "content": "..."}]
             Redis 不可用时返回空列表
    """
    rc = _get_redis()
    if rc is None:
        return []

    try:
        raw_list = rc.lrange(_session_key(user_id), 0, -1)
        history = []
        for raw in raw_list:
            try:
                history.append(json.loads(raw))
            except json.JSONDecodeError:
                logger.warning(f"[memory] 跳过损坏的历史条目: {raw[:50]}")
        return history
    except Exception as e:
        logger.error(f"[memory] get_history 异常 user_id={user_id}: {e}")
        return []


def save_message(user_id: int, role: str, content: str) -> None:
    """
    追加一条消息到用户历史（并触发超限时的摘要压缩）

    :param role: "user" / "assistant"
    :param content: 消息文本
    """
    rc = _get_redis()
    if rc is None:
        return

    msg = json.dumps({"role": role, "content": content}, ensure_ascii=False)
    try:
        rc.rpush(_session_key(user_id), msg)
        # 顺便做一下超限检查
        _maybe_compress(user_id, rc)
    except Exception as e:
        logger.error(f"[memory] save_message 异常 user_id={user_id}: {e}")


def _maybe_compress(user_id: int, rc) -> None:
    """消息超过 MAX_HISTORY_ROUNDS 时做摘要压缩（异步执行，不阻塞主链路）"""
    # RPUSH 完后再查一次长度
    length = rc.llen(_session_key(user_id))
    # 每"一轮"包含 user + assistant 两条消息
    rounds = length // 2
    if rounds <= config.MAX_HISTORY_ROUNDS:
        return

    logger.info(f"[memory] 触发摘要压缩 user_id={user_id}, rounds={rounds}")
    # P0 先简单裁剪：只保留最近 MAX_HISTORY_ROUNDS * 2 条
    # LLM 摘要压缩可以放在 Step 16+ 实现（需要额外的 LLM 调用）
    keep_count = config.MAX_HISTORY_ROUNDS * 2
    try:
        # LTRIM: 保留尾部 keep_count 条（从头部开始删）
        rc.ltrim(_session_key(user_id), -keep_count, -1)
        logger.info(f"[memory] 裁剪完成 user_id={user_id}, 保留最近 {keep_count} 条")
    except Exception as e:
        logger.error(f"[memory] ltrim 异常: {e}")


def clear_history(user_id: int) -> None:
    """清空用户历史（比如用户重置会话）"""
    rc = _get_redis()
    if rc is None:
        return
    try:
        rc.delete(_session_key(user_id))
        logger.info(f"[memory] 已清空 user_id={user_id}")
    except Exception as e:
        logger.error(f"[memory] clear_history 异常: {e}")
