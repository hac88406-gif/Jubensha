"""
全局配置 —— 环境变量优先，带默认值
"""
import os
from pathlib import Path
from dotenv import load_dotenv

# 从项目根目录加载 .env（无论从哪个目录启动都能找到；.env 本身已被 .gitignore 忽略）
_ROOT = Path(__file__).resolve().parents[1]
load_dotenv(_ROOT / ".env")

# ==========================================================================
# Java agent-gateway 地址（Python Agent 通过它间接访问 shop/order-service）
# ==========================================================================
JAVA_BASE_URL = os.getenv("JAVA_BASE_URL", "http://localhost:8085")

# ==========================================================================
# agent-gateway 内部接口鉴权 Key（请求头 X-Internal-Api-Key）
# --------------------------------------------------------------------------
# ⚠️ 必须与 Java 侧实际生效的 urban.internal-api-key 完全一致，否则
#    Python → agent-gateway 的内部调用（查剧本 / 查订单）会全部 HTTP 403，
#    表现为 AI 陪练「检索失败」。
#
# Java 侧的取值优先级为：Nacos urban-shared-config 的 urban.internal-api-key
#                        > 环境变量 URBAN_INTERNAL_API_KEY > application.yml 本地默认值。
# 因此这里不设默认值（源码不留硬编码 Key，避免随仓库公开），
# 请把与 Java 侧一致的值写入项目根目录 .env：
#     INTERNAL_API_KEY=<与 Nacos urban.internal-api-key 相同的值>
# ==========================================================================
INTERNAL_API_KEY = os.getenv("INTERNAL_API_KEY", "")

if not INTERNAL_API_KEY:
    raise RuntimeError(
        "未配置 INTERNAL_API_KEY：请将 Java 侧 urban.internal-api-key 的实际值"
        "（见 Nacos 的 urban-shared-config）填入项目根目录 .env，"
        "或设置为同名环境变量。否则调用 agent-gateway 内部接口会返回 403。"
    )

# ==========================================================================
# LLM 配置 —— 硅基流动 SiliconFlow（DeepSeek-V3）
# --------------------------------------------------------------------------
# SiliconFlow 免费额度注册: https://cloud.siliconflow.cn/
# 兼容 OpenAI API 格式，用 httpx 直接调就行
# ==========================================================================
# ⚠️ 安全：不要在此硬编码真实 Key，请写入项目根目录的 .env（已被 .gitignore 忽略）
# 用法：cp .env.example .env  然后填入自己的 Key
LLM_API_KEY = os.getenv("LLM_API_KEY", "")
LLM_BASE_URL = os.getenv("LLM_BASE_URL", "https://api.deepseek.com/v1")
LLM_MODEL = os.getenv("LLM_MODEL", "deepseek-chat")
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
