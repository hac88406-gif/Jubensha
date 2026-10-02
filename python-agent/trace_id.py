"""
trace_id.py —— 跨语言链路追踪（Python 侧，与 Java 的 X-Trace-Id 对齐）

## 完整链路（本模块补齐其中「断掉」的两段）

    浏览器
      │  POST /api/agent/chat
      ▼
    reservation-gateway         TraceIdGlobalFilter：生成/透传 X-Trace-Id（链路源头）
      │  X-Trace-Id: 4f2a...
      ▼
    agent-gateway               TraceIdFilter：读头 → 写入 MDC → 日志 [4f2a...]
      │  Feign 调用 /api/chat，TraceIdFeignInterceptor 带上 X-Trace-Id   ← Java 侧新增
      ▼
    python-agent（本模块）       ① 读头存入 ContextVar ② 日志输出同一个 ID
      │                          ③ 调 Java internal 接口时回传同一个 ID
      ▼
    agent-gateway /agent/internal/**   TraceIdFilter：读到同一个 ID（链路闭环）
      │  Feign 调用 order-service
      ▼
    order-service               TraceIdFilter：同一个 ID 落到日志

## 为什么用 ContextVar 而不是全局变量 / 参数透传？

- 全局变量：并发请求会互相覆盖（A 请求拿到 B 的 traceId）；
- 参数透传：要改 run_graph → 每个 tool 函数的签名，侵入性太大；
- ContextVar：FastAPI 的同步端点跑在 anyio 线程池里（run_in_threadpool 内部
  copy_context），协程里 set 的值在端点线程内可读，天然按请求隔离。

## 为什么用「纯 ASGI 中间件」而不是 @app.middleware("http")？

@app.middleware("http") 底层是 Starlette 的 BaseHTTPMiddleware，它会把下游应用放到
子任务里执行，ContextVar 能否传到端点依赖 Starlette 版本的实现细节（历史上出过
「中间件里 set 的 cvar 端点读不到」的问题）。纯 ASGI 中间件与路由在同一任务链上
直接 await，传递关系最直接、跨版本最稳定。
"""
from __future__ import annotations

import sys
import uuid
from contextvars import ContextVar, Token

from loguru import logger

# ==========================================================================
# 常量 —— 必须与 Java 侧 TraceIdUtil 保持一致
# ==========================================================================

TRACE_ID_HEADER = "X-Trace-Id"
"""跨语言透传的 HTTP 头名（对齐 Java TraceIdUtil.TRACE_ID_HEADER）"""

MAX_TRACE_ID_LEN = 64
"""超过该长度视为异常输入（防日志注入），丢弃并重建（对齐 Java TraceIdFilter）"""

# ==========================================================================
# 请求上下文存储
# ==========================================================================

_trace_id_var: ContextVar[str | None] = ContextVar("trace_id", default=None)


def generate_trace_id() -> str:
    """
    生成全局唯一 traceId：UUID 去连字符，32 位十六进制

    与 Java 侧 TraceIdUtil.generate() 用同一策略，保证两侧格式观感一致。
    """
    return uuid.uuid4().hex


def get_trace_id() -> str | None:
    """
    取当前请求上下文的 traceId

    :return: 请求内返回 32 位十六进制串；非请求线程（如脚本直连）返回 None
    """
    return _trace_id_var.get()


def set_trace_id(trace_id: str | None) -> Token:
    """
    绑定 traceId 到当前请求上下文

    空值 / 超长（疑似注入）时自动生成兜底，保证「一次请求必有 ID」，
    避免日志里渲染出空串而无法按 ID 聚合。

    :param trace_id: 入站 X-Trace-Id，可为 None
    :return: ContextVar Token，必须在 finally 中交给 reset_trace_id 复位
    """
    tid = (trace_id or "").strip()
    if not tid or len(tid) > MAX_TRACE_ID_LEN:
        tid = generate_trace_id()
    return _trace_id_var.set(tid)


def reset_trace_id(token: Token) -> None:
    """复位 ContextVar（配合 set_trace_id 使用，防上下文复用时串流）"""
    _trace_id_var.reset(token)


# ==========================================================================
# 纯 ASGI 入站中间件
# ==========================================================================

class TraceIdMiddleware:
    """
    入站 traceId 中间件（纯 ASGI）

    职责三件（对齐 Java 侧 TraceIdFilter）：
      1. 读取上游透传的 X-Trace-Id；缺失或超长则本地生成；
      2. 存入 ContextVar，供日志与出站调用（tools/common.py）读取；
      3. 把 traceId 回写响应头，调用方（Java / 压测脚本）可从响应取到本次链路 ID。

    注册方式（main.py）：app.add_middleware(TraceIdMiddleware)
    """

    def __init__(self, app):
        # Starlette 构建中间件栈时按位置传入下游 ASGI app
        self.app = app

    async def __call__(self, scope, receive, send):
        # lifespan / websocket 不是 HTTP 请求链路，直接放行
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        # ASGI headers 形如 [(b"x-trace-id", b"4f2a...")]，HTTP 头大小写不敏感 → 统一小写
        incoming = {
            key.decode("latin-1").lower(): value.decode("latin-1")
            for key, value in (scope.get("headers") or [])
        }
        token = set_trace_id(incoming.get(TRACE_ID_HEADER.lower()))

        # set_trace_id 内部已兜底，此处必为有效值
        trace_id = get_trace_id() or ""
        header_name = TRACE_ID_HEADER.lower().encode("latin-1")
        header_value = trace_id.encode("latin-1")

        async def send_with_trace(message):
            """在响应起始消息上追加 X-Trace-Id 响应头"""
            if message["type"] == "http.response.start":
                headers = message.setdefault("headers", [])
                # 防御：下游若已自行写过该头，不重复追加
                if not any(name == header_name for name, _ in headers):
                    headers.append((header_name, header_value))
            await send(message)

        try:
            await self.app(scope, receive, send_with_trace)
        finally:
            # 请求结束必须复位：同一上下文被复用时不残留上一个请求的 ID
            reset_trace_id(token)


# ==========================================================================
# 日志接入 —— 让 Python 日志与 Java 日志能用同一个 ID grep
# ==========================================================================

def _inject_trace_id(record) -> None:
    """
    loguru patcher：给每条日志记录补 trace_id 字段

    用 setdefault 而非直接赋值：若调用方用 logger.contextualize(trace_id=...) 显式指定，
    以调用方为准（例如后台任务自建 ID 的场景）。
    """
    record["extra"].setdefault("trace_id", get_trace_id() or "-")


def setup_trace_logging() -> None:
    """
    配置 loguru 输出格式，把 traceId 排在时间/级别之后（对齐 Java logback 的 [%X{traceId:-}]）

    效果：
        09:12:33.101 | INFO    | 4f2a1c9b... | [POST /api/chat] userId=1, message=我想约本...

    排障时用同一个 traceId 分别 grep Java 与 Python 日志，即可还原
    「网关 → Lua 扣减 → DB 落库 → MQ 关单 → AI 工具调用」完整时间线。
    """
    logger.remove()  # 移除默认 handler，避免同一条日志输出两遍
    logger.configure(patcher=_inject_trace_id)
    logger.add(
        sys.stderr,
        level="INFO",
        format=(
            "<green>{time:HH:mm:ss.SSS}</green> | "
            "<level>{level: <7}</level> | "
            "<cyan>{extra[trace_id]}</cyan> | "
            "<level>{message}</level>"
        ),
    )