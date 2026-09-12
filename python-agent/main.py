"""
main.py —— FastAPI 入口

路由:
  POST /api/chat   —— 对话主入口（agent-gateway 调用）
  GET  /api/health —— 健康检查
  POST /api/reset  —— 重置会话记忆

启动:
  uvicorn main:app --host 0.0.0.0 --port 8000 --reload
"""
from __future__ import annotations

import sys
from pathlib import Path

# 确保 python-agent 目录在 sys.path 里（uvicorn 启动时 cwd 可能不同）
_CURRENT_DIR = Path(__file__).parent.resolve()
if str(_CURRENT_DIR) not in sys.path:
    sys.path.insert(0, str(_CURRENT_DIR))

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from loguru import logger

import config
from agent.graph import run_graph, get_graph
from schema.chat import ChatRequest, ChatResponse

# ==========================================================================
# Nacos 注册说明（不自动注册，手动注册为持久实例 ephemeral=false）：
#
#   Python Agent 不自动注册 Nacos，原因：
#   1. Nacos 跑在 Docker 里，TCP 探活宿主机 Python 进程不通
#   2. LLM 延迟不可控，"端口可达"≠"服务可用"
#   3. agent-gateway 硬编码调用，不走 Nacos 服务发现
#
#   如需注册（仅为服务拓扑可视化）：
#   Invoke-RestMethod -Uri "http://localhost:8848/nacos/v1/ns/instance" -Method Post -Body @{
#     serviceName="python-agent"; ip="127.0.0.1"; port=8000;
#     groupName="DEFAULT_GROUP"; weight="1.0"; clusterName="DEFAULT";
#     healthy="true"; enabled="true"; ephemeral="false"
#   }
# ==========================================================================

# ==========================================================================
# FastAPI App
# ==========================================================================

app = FastAPI(
    title=config.APP_NAME,
    version=config.APP_VERSION,
    description="Urban Script Reservation Python Agent (FastAPI + LangGraph)",
)

# CORS（开发方便）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ==========================================================================
# 生命周期钩子
# ==========================================================================

@app.on_event("startup")
def on_startup():
    """启动时预构建 LangGraph + 注册 Nacos"""
    logger.info(f"🚀 {config.APP_NAME} v{config.APP_VERSION} starting...")
    logger.info(f"   LLM: {config.LLM_MODEL} @ {config.LLM_BASE_URL}")
    logger.info(f"   Java Gateway: {config.JAVA_BASE_URL}")
    logger.info(f"   Redis: {config.REDIS_URL}")

    # 1. 预构建 LangGraph
    try:
        get_graph()
        logger.info("✅ LangGraph 构建完成，服务就绪")
    except Exception as e:
        logger.error(f"❌ LangGraph 构建失败！服务可能无法正常响应: {e}")
        logger.error("   请执行: pip install -r requirements.txt")


# ==========================================================================
# API 路由
# ==========================================================================

@app.post("/api/chat", response_model=ChatResponse, summary="AI 对话主入口")
def chat(req: ChatRequest):
    """
    对话主入口 —— agent-gateway POST /agent/chat → 转发到这里

    :param req: { user_id, message, session_id?, history? }
    :return: ChatResponse { code, message, intent, data?, error? }
    """
    if not req.message or not req.message.strip():
        raise HTTPException(status_code=400, detail="message 不能为空")

    logger.info(f"[POST /api/chat] userId={req.user_id}, message={req.message[:60]}...")

    try:
        result = run_graph(
            user_message=req.message.strip(),
            user_id=req.user_id,
            session_id=req.session_id,
            history=req.history,
        )
        return ChatResponse(
            code=200,
            message=result["reply"],
            intent=result.get("intent", "chat"),
            data=None,
        )
    except RuntimeError as e:
        # LLM 调用失败（比如 API Key 无效）
        logger.error(f"[chat] 执行失败: {e}")
        return ChatResponse(
            code=500,
            message="AI 回复失败",
            error=str(e),
        )
    except Exception as e:
        logger.exception(f"[chat] 未知异常")
        return ChatResponse(
            code=500,
            message="服务异常，请稍后再试",
            error=str(e),
        )


@app.get("/api/health", summary="健康检查")
def health():
    """agent-gateway 探活用"""
    return {
        "status": "ok",
        "app": config.APP_NAME,
        "version": config.APP_VERSION,
        "llm_model": config.LLM_MODEL,
        "java_gateway": config.JAVA_BASE_URL,
    }


@app.post("/api/reset", summary="重置会话记忆")
def reset_memory(user_id: int):
    """清空某用户的 Redis 会话记忆"""
    from memory import clear_history
    clear_history(user_id)
    return {"status": "ok", "message": f"已清除 user_id={user_id} 的会话记忆"}


# ==========================================================================
# 直接运行入口（python main.py）
# ==========================================================================

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=config.APP_HOST,
        port=config.APP_PORT,
        reload=False,  # reload 在脚本直接运行时保持 False，用 uvicorn 命令启时可加
        log_level="info",
    )
