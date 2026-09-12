<script setup>
import { ref, nextTick, onMounted } from 'vue'
import { useUserStore } from '../../stores/user'
import { fetchAgentChat, resetAgentMemory } from '../../api/agent'

/**
 * AI 陪练对话页（S5）
 *
 * 链路：本页 → /api/agent/chat → agent-gateway → Python Agent（LangGraph + 工具调用）
 * 特性：
 *   - 聊天气泡（玩家右侧科技绿 / 阿本左侧白卡）
 *   - 每条 AI 回复展示意图标签（剧本搜索 / 我的订单 / 查询订单 ...），直观验证意图识别
 *   - 快捷问题一键发送；Enter 发送 / Shift+Enter 换行
 *   - 「新对话」同时清空本地消息与服务端 Redis 会话记忆
 */
const user = useUserStore()

const messages = ref([])
const input = ref('')
const sending = ref(false)
const listRef = ref(null) // 消息列表滚动容器

/** 欢迎语（也是「新对话」后的首条） */
const WELCOME = {
  role: 'assistant',
  content:
    '你好呀，我是「阿本」！可以帮你找剧本、查订单，也可以聊剧本杀玩法。试试下面的快捷问题吧～',
  intent: 'chat',
}

/** 快捷问题（覆盖 剧本搜索 / 按店搜索 / 我的订单 / 闲聊 / 预约引导 五类意图） */
const suggestions = [
  '推荐几个 4 人的硬核本',
  '店铺 3 有什么高分硬核本',
  '我有哪些订单',
  '剧本杀怎么玩',
  '怎么预约场次',
]

/** 意图 → 标签文案 + 颜色（与后端 Intent 枚举对齐） */
const INTENT_MAP = {
  search_script: { label: '剧本搜索', type: 'success' },
  query_order: { label: '查询订单', type: 'warning' },
  query_my_orders: { label: '我的订单', type: 'warning' },
  book_help: { label: '预约引导', type: 'info' },
  chat: { label: '闲聊', type: 'info' },
}

function intentTag(intent) {
  return INTENT_MAP[intent] || { label: intent || '闲聊', type: 'info' }
}

function scrollToBottom() {
  return nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

onMounted(() => {
  if (!messages.value.length) messages.value.push({ ...WELCOME })
})

/**
 * 发送一条消息
 * @param {string} [raw] 指定文本（快捷问题用）；不传则取输入框内容
 */
async function send(raw) {
  const content = (typeof raw === 'string' ? raw : input.value).trim()
  if (!content || sending.value) return
  input.value = ''
  messages.value.push({ role: 'user', content })
  await scrollToBottom()

  sending.value = true
  try {
    // 不传 history：Python Agent 按 user_id 从 Redis 自动拉取会话记忆，避免重复累积
    const resp = await fetchAgentChat({ userId: user.userId, message: content })
    messages.value.push({
      role: 'assistant',
      content: resp?.message || '（本次没有收到回复，请再试一次）',
      intent: resp?.intent || 'chat',
    })
  } catch (e) {
    messages.value.push({
      role: 'assistant',
      content: `抱歉，刚才的请求没有成功（${e.message || '网络异常'}）。请稍后重试，或换个问法～`,
      intent: 'chat',
      error: true,
    })
  } finally {
    sending.value = false
    await scrollToBottom()
  }
}

function pickSuggestion(s) {
  send(s)
}

/** 新对话：清空本地消息 + 重置服务端 Redis 会话记忆（Python Agent 直连） */
async function newChat() {
  messages.value = [{ ...WELCOME }]
  try {
    await resetAgentMemory(user.userId)
  } catch (e) {
    // 重置失败只影响记忆持久性，本地清空照常
    console.warn('[Agent] 重置会话记忆失败（本地已清空展示）', e)
  }
}

/** Enter 发送、Shift+Enter 换行 */
function onEnter(e) {
  if (e.shiftKey) return
  e.preventDefault()
  send()
}
</script>

<template>
  <div class="agent-chat u-card">
    <!-- 头部 -->
    <div class="chat-head">
      <div class="chat-title">
        <div class="ai-badge">阿</div>
        <div>
          <div class="t1">AI 陪练 · 阿本</div>
          <div class="t2">LangGraph 意图识别 + 剧本 / 订单工具调用</div>
        </div>
      </div>
      <el-button size="small" round :disabled="sending" @click="newChat">新对话</el-button>
    </div>

    <!-- 消息列表 -->
    <div ref="listRef" class="chat-list">
      <div
        v-for="(m, i) in messages"
        :key="i"
        class="msg-row"
        :class="m.role"
      >
        <div class="avatar" :class="m.role">{{ m.role === 'assistant' ? '阿' : '我' }}</div>
        <div class="msg-body">
          <div class="msg-bubble" :class="{ error: m.error }">{{ m.content }}</div>
          <div v-if="m.role === 'assistant'" class="msg-meta">
            <el-tag size="small" :type="intentTag(m.intent).type" effect="plain" round>
              {{ intentTag(m.intent).label }}
            </el-tag>
          </div>
        </div>
      </div>

      <!-- 思考中 -->
      <div v-if="sending" class="msg-row assistant">
        <div class="avatar assistant">阿</div>
        <div class="msg-body">
          <div class="msg-bubble typing"><span /><span /><span /></div>
        </div>
      </div>
    </div>

    <!-- 快捷问题 -->
    <div v-if="!sending" class="suggests">
      <el-tag
        v-for="(s, i) in suggestions"
        :key="i"
        class="suggest"
        effect="plain"
        round
        @click="pickSuggestion(s)"
      >
        {{ s }}
      </el-tag>
    </div>

    <!-- 输入区 -->
    <div class="chat-input">
      <el-input
        v-model="input"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 4 }"
        placeholder="和阿本聊聊：找剧本、查订单、问玩法…"
        resize="none"
        @keydown.enter="onEnter"
      />
      <el-button
        type="primary"
        class="send-btn"
        :loading="sending"
        :disabled="!input.trim()"
        @click="send()"
      >
        发送
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.agent-chat {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 130px);
  min-height: 520px;
  overflow: hidden;
}

/* ---------- 头部 ---------- */
.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--color-border);
}

.chat-title {
  display: flex;
  align-items: center;
  gap: 12px;
}

.ai-badge {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--color-emerald), var(--color-cyan));
  color: #fff;
  font-size: 17px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(6, 182, 212, 0.3);
}

.t1 {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-main);
}

.t2 {
  font-size: 12px;
  color: var(--color-text-sub);
}

/* ---------- 消息列表 ---------- */
.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  background: linear-gradient(180deg, #f8fafc 0%, #f0fdfa 100%);
}

.msg-row {
  display: flex;
  gap: 10px;
  max-width: 100%;
}

.msg-row.user {
  flex-direction: row-reverse;
}

.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}

.avatar.assistant {
  background: linear-gradient(135deg, var(--color-emerald), var(--color-cyan));
}

.avatar.user {
  background: #94a3b8;
}

.msg-body {
  max-width: 72%;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.msg-row.user .msg-body {
  align-items: flex-end;
}

.msg-bubble {
  padding: 10px 14px;
  border-radius: 14px;
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.assistant .msg-bubble {
  background: #fff;
  border: 1px solid var(--color-border);
  box-shadow: 0 2px 8px rgba(13, 148, 136, 0.05);
  border-top-left-radius: 4px;
}

.user .msg-bubble {
  background: linear-gradient(135deg, var(--color-emerald), #0f766e);
  color: #fff;
  border-top-right-radius: 4px;
}

.msg-bubble.error {
  background: #fef2f2;
  border-color: #fecaca;
  color: #b91c1c;
}

.msg-meta {
  display: flex;
}

/* 思考中三点动画 */
.typing {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  min-height: 26px;
  padding: 10px 16px;
}

.typing span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--color-emerald);
  animation: blink 1.2s infinite;
}

.typing span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%,
  100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  50% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

/* ---------- 快捷问题 ---------- */
.suggests {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 20px 0;
}

.suggest {
  cursor: pointer;
  transition: all 0.2s;
}

.suggest:hover {
  color: var(--color-emerald);
  border-color: var(--color-emerald);
  background: rgba(13, 148, 136, 0.08);
}

/* ---------- 输入区 ---------- */
.chat-input {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 14px 20px 16px;
  border-top: 1px solid var(--color-border);
}

.chat-input .el-input {
  flex: 1;
}

.send-btn {
  height: 38px;
  min-width: 92px;
  border-radius: 10px;
}
</style>