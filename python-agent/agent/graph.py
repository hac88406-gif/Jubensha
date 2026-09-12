"""
agent/graph.py —— LangGraph 核心编排

## 图结构（4 节点 + 条件路由）
```
  START ──► recognize_intent ──► route_by_intent
                                   │
      ┌────────────────────────────┼──────────────────────────┐
      │                            │                          │
      ▼                            ▼                          ▼
call_tool ──► generate_reply  ◄──  (chat/book_help 直接进 generate_reply)
  │
  └──────────► END
```

## State 字段
- messages: list[dict]    —— 多轮消息 [{role, content}, ...]
- intent: str             —— recognize_intent 产出
- tool_results: list[dict] —— call_tool 产出
- user_id / session_id

## 单例模式
compiled graph 做成进程级单例，API 层只调用 run_graph(message, user_id)。
"""
from __future__ import annotations

import json
from typing import Any, Optional

from loguru import logger

import config
from agent import prompts
from schema.chat import AgentState, Intent
from tools import order_tool, script_tool


# ==========================================================================
# LLM 客户端（httpx 直连 OpenAI 兼容接口）
# ==========================================================================

def _call_llm(system_prompt: str, messages: list[dict[str, str]], **kwargs) -> str:
    """
    调 LLM（硅基流动 DeepSeek-V3，兼容 OpenAI /chat/completions 格式）

    :param system_prompt: 系统提示词
    :param messages: 对话历史（不含 system，函数内部自动拼）
    :return: LLM 回复文本
    """
    import httpx

    payload = {
        "model": config.LLM_MODEL,
        "temperature": config.LLM_TEMPERATURE,
        "max_tokens": config.LLM_MAX_TOKENS,
        "messages": [
            {"role": "system", "content": system_prompt},
            *messages,
        ],
    }
    # 合并 kwargs（如果调用方传了 extra_body 之类）
    payload.update(kwargs)

    try:
        with httpx.Client(timeout=30.0) as c:
            resp = c.post(
                f"{config.LLM_BASE_URL.rstrip('/')}/chat/completions",
                headers={
                    "Authorization": f"Bearer {config.LLM_API_KEY}",
                    "Content-Type": "application/json",
                },
                json=payload,
            )
            logger.info(f"[_call_llm] HTTP {resp.status_code}, body_len={len(resp.text)}, body_preview={resp.text[:200]}")
            resp.raise_for_status()
            data = resp.json()
            content = data["choices"][0]["message"]["content"].strip()
            logger.info(f"[_call_llm] content={content[:100]}")
            return content
    except httpx.RequestError as e:
        logger.error(f"[_call_llm] 网络异常: {e}")
        raise RuntimeError(f"LLM 网络异常: {e}")
    except Exception as e:
        logger.error(f"[_call_llm] 未知异常: {e}")
        raise RuntimeError(f"LLM 调用失败: {e}")


# ==========================================================================
# LangGraph 节点函数（每个接收 State dict，返回增量更新 dict）
# ==========================================================================

def _extract_json_object(text: str) -> dict | None:
    """
    从 LLM 输出中尽量提取 JSON 对象（意图分类用）

    LLM 偶尔会输出散文 / markdown 代码块，这里做三重兜底：
      ① 剥 ```json ``` 代码块后直接 json.loads
      ② 取首个 { 到末个 } 之间的子串再 load
      ③ 都失败返回 None（调用方回退 chat）
    """
    if not text:
        return None
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = cleaned.strip("`").strip()
        if cleaned.startswith("json"):
            cleaned = cleaned[4:].strip()
    try:
        return json.loads(cleaned)
    except (json.JSONDecodeError, TypeError):
        pass
    # 兜底：截取首尾大括号之间的内容
    start, end = cleaned.find("{"), cleaned.rfind("}")
    if start != -1 and end > start:
        try:
            return json.loads(cleaned[start:end + 1])
        except (json.JSONDecodeError, TypeError):
            pass
    return None


def recognize_intent(state: AgentState) -> dict:
    """
    节点 ① —— 意图分类 + 参数提取

    升级：让 LLM 同时输出 intent + order_no + script_type + player_cnt，
    一次调用拿到所有信息，避免分两次 LLM 调用。

    注意：分类器只喂「最后一条用户消息」——完整历史（尤其当助手已答过同一问题时）
    会让 LLM 倾向于接着聊天而不是输出 JSON 分类结果。
    """
    # 只取待分类的那条用户输入（去掉多轮历史，保证分类稳定性）
    llm_input = state.messages[-1:]

    intent = Intent.CHAT.value
    params: dict[str, Any] = {}

    try:
        # 分类是确定性任务，用低温度避免 LLM"自由发挥"输出散文而非 JSON
        raw = _call_llm(prompts.INTENT_CLASSIFIER_SYSTEM, llm_input, temperature=0.1)
        parsed = _extract_json_object(raw)

        if not isinstance(parsed, dict):
            raise ValueError("分类器未返回 JSON 对象")

        intent = parsed.get("intent", Intent.CHAT.value)
        # 提取字符串参数（null 就设成 None）
        for key in ("order_no", "script_type", "script_name", "tag"):
            val = parsed.get(key)
            if val and val != "null":
                params[key] = val
        # player_cnt / shop_id 可能是字符串 "null" / 数字
        for num_key in ("player_cnt", "shop_id"):
            nv = parsed.get(num_key)
            if nv is not None and nv != "null":
                try:
                    params[num_key] = int(nv)
                except (ValueError, TypeError):
                    pass

    except (json.JSONDecodeError, RuntimeError, Exception) as e:
        logger.warning(f"[recognize_intent] LLM 解析失败，默认 chat: {e}")
        intent = Intent.CHAT.value

    logger.info(f"[recognize_intent] ✅ 意图={intent}, params={params}")
    return {"intent": intent, "intent_params": params}


def call_tool(state: AgentState) -> dict:
    """
    节点 ② —— 调工具（根据 intent + intent_params 分发）

    recognize_intent 已经从 LLM 拿到 intent + 提取的参数（order_no / script_type / player_cnt），
    这里直接消费，不再正则硬编码。
    """
    intent = state.intent
    user_id = state.user_id
    params = state.intent_params or {}

    result: dict[str, Any] = {"intent": intent, "raw": None}

    try:
        if intent == Intent.SEARCH_SCRIPT.value:
            script_type = params.get("script_type")
            player_cnt = params.get("player_cnt")
            shop_id = params.get("shop_id")
            logger.info(
                f"[call_tool] search_script: type={script_type}, "
                f"playerCnt={player_cnt}, shopId={shop_id}"
            )
            tool_raw = script_tool.search_scripts(script_type, player_cnt, shop_id)
            result["raw"] = tool_raw

        elif intent == Intent.QUERY_ORDER.value:
            order_no = params.get("order_no")
            if not order_no:
                result["raw"] = {
                    "error": "未找到订单号",
                    "hint": "请在消息里包含 ORD 开头的订单号，或尝试“我约的场”让系统查全部订单",
                }
            else:
                logger.info(f"[call_tool] query_order: orderNo={order_no}")
                tool_raw = order_tool.query_order(order_no, user_id)
                result["raw"] = tool_raw

        elif intent == Intent.QUERY_MY_ORDERS.value:
            if not user_id:
                result["raw"] = {"error": "请先登录（user_id 必填）"}
            else:
                logger.info(f"[call_tool] list_my_orders: userId={user_id}")
                tool_raw = order_tool.list_my_orders(user_id)
                result["raw"] = tool_raw

        elif intent in (Intent.PLOT_QA.value, Intent.CHARACTER_INTRO.value):
            # 剧情问答 / 角色介绍 —— 先按书名解析 ID，再拉详情（含 background/characters）
            script_name = params.get("script_name")
            if not script_name:
                result["raw"] = {
                    "error": "未识别出剧本名",
                    "hint": "请把剧本名发给我，比如“介绍下《海上列车谋杀案》的角色”",
                }
            else:
                script_id = script_tool.resolve_script_id(script_name)
                if script_id is None:
                    result["raw"] = {
                        "error": f"未找到《{script_name}》",
                        "hint": "试试说出剧本的完整名字？",
                    }
                else:
                    logger.info(f"[call_tool] {intent}: scriptId={script_id}")
                    tool_raw = script_tool.get_script_detail(script_id)
                    result["raw"] = tool_raw

        elif intent == Intent.SIMILAR_SCRIPT.value:
            # Neo4j 关系查询 —— 和某本类似的剧本
            script_name = params.get("script_name")
            if not script_name:
                result["raw"] = {
                    "error": "未识别出剧本名",
                    "hint": "请告诉我剧本名，比如“和《归途》类似的剧本还有哪些”",
                }
            else:
                script_id = script_tool.resolve_script_id(script_name)
                if script_id is None:
                    result["raw"] = {"error": f"未找到《{script_name}》"}
                else:
                    logger.info(f"[call_tool] similar_script: scriptId={script_id}")
                    tool_raw = script_tool.similar_scripts(script_id)
                    result["raw"] = tool_raw

        elif intent == Intent.SMART_PICK.value:
            # 智能选本 —— Cypher 过滤（类型/人数/细标签组合）
            logger.info(f"[call_tool] smart_pick: {params}")
            tool_raw = script_tool.smart_pick(
                params.get("script_type"),
                params.get("player_cnt"),
                params.get("tag"),
            )
            result["raw"] = tool_raw

        elif intent == Intent.CANCEL_ORDER.value:
            # 取消订单 —— 按 ORD 订单号取消（需登录，归属校验）
            order_no = params.get("order_no")
            if not order_no:
                result["raw"] = {
                    "error": "未找到订单号",
                    "hint": "请把要取消的 ORD 订单号发给我，比如“取消 ORD20260903”",
                }
            else:
                logger.info(f"[call_tool] cancel_order: orderNo={order_no}, userId={user_id}")
                tool_raw = order_tool.cancel_order(order_no, user_id)
                result["raw"] = tool_raw

        else:
            # route_by_intent 应该拦住了（chat/book_help 不走 call_tool），防御一下
            result["raw"] = {"note": "此意图不需要工具"}

    except Exception as e:
        logger.error(f"[call_tool] 异常: {e}")
        result["raw"] = {"error": f"工具调用异常: {e}"}

    logger.info(f"[call_tool] ✅ intent={intent}, params={params}, raw_type={type(result['raw']).__name__}")
    return {"tool_results": [result]}


def generate_reply(state: AgentState) -> dict:
    """
    节点 ③ —— 生成最终回复

    把通用 system prompt + 完整历史（messages） + 可选工具结果
    一起喂 LLM，产出最终回复文本。
    """
    messages = state.messages
    intent = state.intent
    tool_results = state.tool_results

    # 把工具结果格式化后追加到 history 里作为上下文
    extra_context = ""
    if tool_results:
        last_tool = tool_results[-1]  # 取最新一次工具结果
        raw = last_tool.get("raw")
        extra_context = f"\n\n【工具调用结果（参考）】\n{prompts.format_tool_result(intent, raw)}"

    # 构造 LLM 输入：system + 历史 + 可选工具结果
    # 历史里已经有 role 区分，直接传
    try:
        final_prompt = prompts.GENERAL_SYSTEM + extra_context
        reply = _call_llm(final_prompt, messages)
    except RuntimeError as e:
        logger.error(f"[generate_reply] LLM 调用失败: {e}")
        reply = "抱歉，AI 回复服务暂时不可用，请稍后再试。"

    logger.info(f"[generate_reply] ✅ reply={reply[:80]}...")
    return {"messages": [{"role": "assistant", "content": reply}]}


def route_by_intent(state: AgentState) -> str:
    """
    条件路由函数 —— LangGraph add_conditional_edges 用

    :return: "call_tool" 或 "generate_reply"
    """
    intent = state.intent
    tool_intents = {
        Intent.QUERY_ORDER.value,
        Intent.SEARCH_SCRIPT.value,
        Intent.QUERY_MY_ORDERS.value,
        Intent.PLOT_QA.value,
        Intent.CHARACTER_INTRO.value,
        Intent.SIMILAR_SCRIPT.value,
        Intent.SMART_PICK.value,
        Intent.CANCEL_ORDER.value,
    }
    if intent in tool_intents:
        return "call_tool"
    # chat / book_help / 兜底
    return "generate_reply"


# ==========================================================================
# 图构建（进程级单例）
# ==========================================================================

def _build_graph():
    """构建并编译 LangGraph StateGraph"""
    try:
        from langgraph.graph import StateGraph, END
    except ImportError as e:
        logger.error(f"[graph] LangGraph 未安装！请先 pip install langgraph: {e}")
        raise

    sg = StateGraph(AgentState)

    # 1. 加节点
    sg.add_node("recognize_intent", recognize_intent)
    sg.add_node("call_tool", call_tool)
    sg.add_node("generate_reply", generate_reply)

    # 2. 边：START → recognize_intent
    sg.set_entry_point("recognize_intent")

    # 3. 条件路由：识别意图后决定走 call_tool 还是 generate_reply
    sg.add_conditional_edges(
        "recognize_intent",
        route_by_intent,
        {
            "call_tool": "call_tool",
            "generate_reply": "generate_reply",
        },
    )

    # 4. 收尾
    sg.add_edge("call_tool", "generate_reply")
    sg.add_edge("generate_reply", END)

    return sg.compile()


# 进程级单例（import 时懒加载，如果 langgraph 没装会在此时报错）
_graph = None


def get_graph():
    global _graph
    if _graph is None:
        logger.info("[graph] 🧱 构建 LangGraph...")
        _graph = _build_graph()
        logger.info("[graph] ✅ LangGraph 构建完成")
    return _graph


# ==========================================================================
# 对外主入口（FastAPI main.py 只调这一个函数）
# ==========================================================================

def run_graph(user_message: str, user_id: Optional[int] = None, session_id: Optional[int] = None,
              history: Optional[list[dict]] = None) -> dict:
    """
    执行一次完整对话流程

    :param user_message: 用户最新输入
    :param user_id: 用户 ID（会话记忆 + 工具查询用）
    :param session_id: 可选场次 ID
    :param history: 可选外部传入的历史（不传则用 Redis 会话记忆）
    :return: {"reply": str, "intent": str}
    """
    from memory import get_history, save_message  # lazy import 避免循环依赖

    # 构造初始 state
    initial_messages: list[dict[str, str]] = []

    if history:
        # 调用方显式传了 history（Redis 已启用时不会走到这里，用 get_history 自动拉）
        initial_messages = [{"role": m["role"], "content": m["content"]} for m in history]
    elif user_id:
        # 从 Redis 拉历史
        initial_messages = get_history(user_id)

    # 追加用户最新输入
    initial_messages.append({"role": "user", "content": user_message})

    state = AgentState(
        messages=initial_messages,
        intent="",
        tool_results=[],
        user_id=user_id,
        session_id=session_id,
    )

    compiled = get_graph()

    try:
        # LangGraph invoke：同步运行整图，返回最终 state dict
        final_state = compiled.invoke(state.model_dump())
    except Exception as e:
        logger.error(f"[run_graph] 图执行异常: {e}", exc_info=True)
        reply = f"服务暂时不可用，请稍后再试。错误: {e}"
        intent = "chat"
    else:
        # 取最后一条 assistant 消息作为 reply
        final_messages = final_state.get("messages", [])
        reply = ""
        for msg in reversed(final_messages):
            if msg.get("role") == "assistant":
                reply = msg.get("content", "")
                break
        intent = final_state.get("intent", "chat")

    # 保存到 Redis（如果 user_id 有）
    if user_id:
        try:
            save_message(user_id, "user", user_message)
            if reply:
                save_message(user_id, "assistant", reply)
        except Exception as e:
            logger.warning(f"[run_graph] 保存会话记忆失败（不影响主链路）: {e}")

    return {"reply": reply, "intent": intent}
