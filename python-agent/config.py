"""
全局配置 —— 环境变量优先，带默认值
"""
import os
from dotenv import load_dotenv

load_dotenv()  # 加载 .env（如果有）

# ==========================================================================
# Java agent-gateway 地址（Python Agent 通过它间接访问 shop/order-service）
# ==========================================================================
JAVA_BASE_URL = os.getenv("JAVA_BASE_URL", "http://localhost:8085")

# agent-gateway 内部接口鉴权 Key（与 Java 三服务 Filter/Interceptor 的 fallback 值完全一致）
# Bug 修复：原默认值 "test-api-key" 与 Java 侧 InternalApiKeyFilter 的兜底值
# "urban-internal-api-key-dev-fallback" 不一致，Nacos 不可达时所有 Python→Java
# 内部接口调用 (查订单/查剧本/查我的订单) 全部返回 HTTP 403 "服务间鉴权失败"（P0）。
# 环境变量优先，设置了 INTERNAL_API_KEY 仍然覆盖本默认值。
INTERNAL_API_KEY = os.getenv("INTERNAL_API_KEY", "urban-internal-api-key-dev-fallback")

# ==========================================================================
# LLM 配置 —— 硅基流动 SiliconFlow（DeepSeek-V3）
# --------------------------------------------------------------------------
# SiliconFlow 免费额度注册: https://cloud.siliconflow.cn/
# 兼容 OpenAI API 格式，用 httpx 直接调就行
# ==========================================================================
LLM_API_KEY = os.getenv("LLM_API_KEY", "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")  # 占位，请替换
LLM_BASE_URL = os.getenv("LLM_BASE_URL", "https://api.siliconflow.cn/v1")
LLM_MODEL = os.getenv("LLM_MODEL", "deepseek-ai/DeepSeek-V3")
LLM_TEMPERATURE = float(os.getenv("LLM_TEMPERATURE", "0.7"))
LLM_MAX_TOKENS = int(os.getenv("LLM_MAX_TOKENS", "1024"))

# ==========================================================================
# Redis 配置（会话记忆）
# ==========================================================================
REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379/1")  # DB 1

# ==========================================================================
# 会话记忆参数
# ==========================================================================
MAX_HISTORY_ROUNDS = int(os.getenv("MAX_HISTORY_ROUNDS", "10"))  # 超过 N 轮触发摘要压缩

# ==========================================================================
# FastAPI
# ==========================================================================
APP_NAME = "urban-script-reservation-python-agent"
APP_VERSION = "1.0.0"
APP_HOST = os.getenv("APP_HOST", "0.0.0.0")
APP_PORT = int(os.getenv("APP_PORT", "8000"))
