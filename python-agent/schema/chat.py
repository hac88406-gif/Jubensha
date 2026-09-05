"""
Pydantic 模型 —— ChatRequest / ChatResponse / Intent
"""
from __future__ import annotations
from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, Field


# ==========================================================================
# 意图枚举（LangGraph route_by_intent 节点据此分支）
# ==========================================================================

class Intent(str, Enum):
    """
    Agent 可识别的意图类型。
    LangGraph 的 route_by_intent 节点会把 LLM 分类结果映射到这里。
    """
    # ---- 工具调用类 ----
    QUERY_ORDER = "query_order"       # 查订单："我那个单怎么样了" / "ORDxxx"
    SEARCH_SCRIPT = "search_script"   # 找剧本："推荐适合 6 人玩的情感本"
    QUERY_MY_ORDERS = "query_my_orders"  # 我的订单列表

    # ---- 通用对话 ----
    CHAT = "chat"                     # 闲聊 / 咨询："剧本杀怎么玩" / "你好"
    BOOK_HELP = "book_help"           # 引导下单："怎么预约" → 给出引导语


# ==========================================================================
# 请求 / 响应
# ==========================================================================

class ChatRequest(BaseModel):
    """FastAPI /api/chat 请求体 —— 与 agent-gateway 的 ChatRequest 字段对齐"""
    user_id: Optional[int] = Field(default=None, description="用户 ID，会话记忆用")
    message: str = Field(..., description="用户最新输入")
    session_id: Optional[int] = Field(default=None, description="可选：当前场次 ID")

    # 历史消息（多轮上下文；Redis 会话记忆启用时可不传，Agent 自己查）
    history: Optional[list[dict[str, str]]] = None


class ToolCall(BaseModel):
    """记录一次工具调用（便于前端调试 / 日志）"""
    tool_name: str
    args: dict[str, Any] = {}
    result_summary: str = ""


class ChatResponse(BaseModel):
    """FastAPI /api/chat 响应体 —— 与 agent-gateway 的 ChatResponse 字段对齐"""
    code: int = 200
    message: str                                 # Agent 回复文本
    intent: Optional[str] = None                 # 意图（query_order / search_script / chat ...）
    data: Optional[Any] = None                   # 可选：结构化数据（如推荐剧本列表）
    tool_calls: Optional[list[ToolCall]] = None  # 可选：本次对话的工具调用痕迹
    error: Optional[str] = None                  # 错误时填充（code != 200）


# ==========================================================================
# LangGraph State（图运行期状态）
# ==========================================================================

class AgentState(BaseModel):
    """
    LangGraph 图运行期状态 —— 传递在各节点之间。
    注意：LangGraph 实际用 TypedDict 也可以，但用 Pydantic BaseModel
    更方便 IDE 类型提示 + JSON 序列化。
    """
    messages: list[dict[str, str]] = []   # 多轮消息（[{role, content}, ...]）
    intent: str = ""                       # 识别出的意图
    # LLM 同时提取的工具参数（recognize_intent 填充，call_tool 消费）
    # 可能包含: order_no, script_type, player_cnt
    intent_params: dict[str, Any] = {}
    tool_results: list[dict[str, Any]] = []  # 工具调用结果
    user_id: Optional[int] = None
    session_id: Optional[int] = None
