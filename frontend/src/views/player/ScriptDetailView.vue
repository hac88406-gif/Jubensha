<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchScriptDetail, fetchSessionsByScript } from '../../api/shop'
import { fetchSimilar } from '../../api/recommend'
import { createOrder, prepay, payNotify } from '../../api/order'
import { useUserStore } from '../../stores/user'

/**
 * 剧本详情页
 * 剧本信息 + 场次列表（可约场次）：选择场次 → 下单（占用库存） → 支付。
 * 支付演示流程（与后端"模拟支付"对齐）：
 *   1) 下单 createOrder  → 返回 orderNo（待支付）
 *   2) prepay            → 返回 paymentNo/sign（模拟"拉起支付"）
 *   3) payNotify         → 前端模拟"支付平台异步回调"，校验签名后入账
 * 完全走真实后端，非静态 mock。
 */
const route = useRoute()
const router = useRouter()
const user = useUserStore()

// 当前剧本 id 从路由实时读取（猜你喜欢跳转同路由时组件复用，需用最新值）
const scriptId = () => Number(route.params.id)
const script = ref(null)
const sessions = ref([])
const loading = ref(false)
const booking = ref(false)

// 「猜你喜欢」：同标签 / 同作者 相似推荐（6 条）
const similar = ref([])

// 人物介绍：详情接口返回的 characters 数组 [{name,gender,age,desc,image}]，
// 过滤无名字的脏数据；服务端列表接口已剥离该字段，仅详情页可拿到
const charList = computed(() => {
  const cs = script.value?.characters
  return Array.isArray(cs) ? cs.filter((c) => c && c.name) : []
})

// 性别：1=男 2=女 其他=未知（对齐爬虫 gender 语义）
function genderText(g) {
  if (g === 2) return '女'
  if (g === 1) return '男'
  return '未知'
}
// 每个角色独立的样式类，用于渐变头像背景色区分性别
function genderCls(g) {
  if (g === 2) return 'female'
  if (g === 1) return 'male'
  return 'unknown'
}

// 下单弹窗状态
const payDialog = ref(false)
const currentSession = ref(null)
const playerCnt = ref(1)
const payMethod = ref(1)

// 场次状态：1-开放可预约 0-已关闭（对齐后端 SessionRes.status：1 开放 / 0 已关闭）
const sessionStatusMap = { 1: '开放中', 0: '已关闭' }

async function load() {
  loading.value = true
  try {
    const id = scriptId()
    const [s, ss, sim] = await Promise.all([
      fetchScriptDetail(id),
      fetchSessionsByScript(id),
      // 相似推荐：失败静默，不影响主内容
      fetchSimilar(id).catch(() => []),
    ])
    script.value = s
    sessions.value = Array.isArray(ss) ? ss : []
    similar.value = Array.isArray(sim) ? sim : []
  } catch (e) {
    ElMessage.error('加载剧本详情失败')
  } finally {
    loading.value = false
  }
}

function openPay(session) {
  // status=1 开放可预约；0=已关闭不可预约
  if (session.status !== 1) {
    ElMessage.warning('该场次不可预约')
    return
  }
  currentSession.value = session
  playerCnt.value = 1
  payMethod.value = 1
  payDialog.value = true
}

function fmtPrice(v) {
  if (v === null || v === undefined) return '-'
  return `¥${v}`
}

function fmtTime(t) {
  if (!t) return ''
  return typeof t === 'string' && t.length >= 5 ? t.slice(0, 5) : String(t)
}

async function confirmOrder() {
  if (!currentSession.value) return
  booking.value = true
  try {
    // 1) 下单 → orderNo
    const orderNo = await createOrder({
      sessionId: currentSession.value.id,
      playerCnt: playerCnt.value,
    })
    ElMessage.success('下单成功，正在拉起支付...')

    // 2) 发起支付 → paymentNo/sign
    const pay = await prepay({ orderNo, payMethod: payMethod.value })

    // 3) 模拟支付完成 → 异步回调 notify（回传支付侧 sign）
    await payNotify({
      paymentNo: pay.paymentNo,
      orderNo: pay.orderNo,
      amount: String(pay.amount),
      channel: pay.channel,
      sign: pay.sign,
    })
    ElMessage.success('支付成功，座位已锁定！')
    payDialog.value = false
    router.push('/player/orders')
  } catch (e) {
    // 错误已由 http 拦截器统一提示
    ElMessage.error(e.message || '下单失败')
  } finally {
    booking.value = false
  }
}

// 详情页内跳转到猜你喜欢剧本：同路由复用组件，需 watch 路由参数重新加载
function goDetail(id) {
  router.push(`/player/script/${id}`)
}

// 路由参数变化（同路由不同剧本）时重新加载全部数据
watch(
  () => route.params.id,
  () => {
    load()
  }
)

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="detail-page">
    <!-- 剧本信息 -->
    <el-card shadow="never" class="head-card">
      <div class="head-row">
        <!-- 封面：有图用图，否则渐变占位 + 剧本名首字 -->
        <img v-if="script?.image" :src="script.image" class="head-cover" alt="" />
        <div v-else class="head-cover head-cover-placeholder">{{ (script?.name || '?').slice(0, 1) }}</div>

        <div class="head-main">
          <div class="title-line">
            <h2 class="name">{{ script?.name }}</h2>
            <el-tag size="small" type="info" effect="plain">{{ script?.scriptType }}</el-tag>
          </div>
          <p class="meta">
            {{ script?.playerMin }}-{{ script?.playerMax }} 人 · {{ script?.duration }} 分钟 · 作者 {{ script?.author || '-' }}
          </p>
          <!-- 评分：半星精度 + 评分数字 + 评价人数 -->
          <div class="rating-line">
            <el-rate
              v-if="script?.mark !== null && script?.mark !== undefined"
              :model-value="Number(script.mark)"
              disabled
              allow-half
              class="rate"
            />
            <span v-if="script?.mark !== null && script?.mark !== undefined" class="mark-num">{{ script.mark }}</span>
            <span v-if="script?.markCnt" class="mark-cnt">（{{ script.markCnt }} 人评分）</span>
            <span v-else-if="script?.mark === null || script?.mark === undefined" class="no-rating">暂无评分</span>
          </div>
          <!-- 性别构成：男/女/未知人数（0 则为空位，人数为全 0 时整体不展示） -->
          <div v-if="[script?.maleNum, script?.femaleNum, script?.unknownNum].some((n) => n)" class="gender-line">
            <el-tag size="small" effect="plain" type="info">♂ {{ script?.maleNum ?? 0 }} 男</el-tag>
            <el-tag size="small" effect="plain" type="info">♀ {{ script?.femaleNum ?? 0 }} 女</el-tag>
            <el-tag v-if="script?.unknownNum" size="small" effect="plain" type="info">{{ script.unknownNum }} 未知</el-tag>
          </div>
          <!-- 细标签 -->
          <div v-if="script?.tags" class="tag-line">
            <el-tag size="small" type="warning" effect="light">{{ script.tags }}</el-tag>
          </div>
        </div>

        <div class="head-price">{{ fmtPrice(script?.price) }}</div>
      </div>
    </el-card>

    <!-- 剧情介绍 -->
    <div v-if="script?.background" class="intro-section">
      <h3 class="section-title">剧情介绍</h3>
      <el-card shadow="never" class="intro-card">
        <p class="intro-text">{{ script.background }}</p>
      </el-card>
    </div>

    <!-- 人物介绍：名字 / 性别 / 年龄 / 简介，无角色数据（如测试本）自动隐藏 -->
    <div v-if="charList.length" class="intro-section">
      <h3 class="section-title">人物介绍</h3>
      <el-card shadow="never" class="intro-card">
        <div class="char-grid">
          <div v-for="c in charList" :key="c.name" class="char-card">
            <!-- 角色头像：有图用图，无图用性别色渐变占位 + 首字 -->
            <img v-if="c.image" :src="c.image" class="char-avatar" alt="" />
            <div v-else class="char-avatar char-avatar-text" :class="genderCls(c.gender)">
              {{ (c.name || '?').slice(0, 1) }}
            </div>
            <div class="char-info">
              <div class="char-name-line">
                <span class="char-name">{{ c.name }}</span>
                <span class="char-gender" :class="genderCls(c.gender)">{{ genderText(c.gender) }}</span>
                <span v-if="c.age" class="char-age">{{ c.age }} 岁</span>
              </div>
              <p class="char-desc">{{ c.desc || '暂无人物简介' }}</p>
            </div>
          </div>
        </div>
      </el-card>
    </div>

    <!-- 场次列表 -->
    <h3 class="section-title">可选场次</h3>
    <el-empty v-if="!loading && sessions.length === 0" description="暂无场次，敬请期待" />
    <div v-loading="loading" class="session-list">
      <el-card v-for="s in sessions" :key="s.id" shadow="hover" class="session-card">
        <div class="s-left">
          <div class="s-date">🕐 {{ s.sessionDate }} {{ fmtTime(s.startTime) }}-{{ fmtTime(s.endTime) }}</div>
          <div class="s-meta" v-if="s.shopName">📍 {{ s.shopName }}</div>
        </div>
        <div class="s-mid">
          <el-tag size="small" :type="s.status === 1 ? 'success' : 'info'">
            {{ sessionStatusMap[s.status] || '未知' }}
          </el-tag>
          <span class="stock">剩 {{ s.remaining }} / {{ s.capacity }} 座</span>
        </div>
        <div class="s-right">
          <el-button
            type="primary"
            size="small"
            :disabled="s.status !== 1 || s.remaining <= 0"
            @click="openPay(s)"
          >
            {{ s.status === 1 ? '预约' : sessionStatusMap[s.status] }}
          </el-button>
        </div>
      </el-card>
    </div>

    <!-- 猜你喜欢：同标签 / 同作者 相似剧本（无数据不展示） -->
    <div v-if="similar.length > 0" class="similar-section">
      <h3 class="section-title">猜你喜欢</h3>
      <div class="similar-scroll">
        <div
          v-for="r in similar"
          :key="r.scriptId"
          class="similar-card"
          @click="goDetail(r.scriptId)"
        >
          <img v-if="r.image" :src="r.image" class="similar-cover" alt="" />
          <div v-else class="similar-cover similar-cover-placeholder">{{ (r.name || '?').slice(0, 1) }}</div>
          <div class="similar-info">
            <p class="similar-name">{{ r.name }}</p>
            <div class="similar-foot">
              <el-tag size="small" type="info" effect="plain">{{ r.scriptType }}</el-tag>
              <span v-if="r.mark !== null && r.mark !== undefined" class="similar-mark">评分 {{ r.mark }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 下单 / 支付弹窗 -->
    <el-dialog v-model="payDialog" title="预约下单" width="420px" :close-on-click-modal="false">
      <div class="pay-info">
        <p>场次：{{ currentSession?.sessionDate }} {{ fmtTime(currentSession?.startTime) }}-{{ fmtTime(currentSession?.endTime) }}</p>
        <div class="cnt-row">
          <span>预约人数</span>
          <el-input-number v-model="playerCnt" :min="1" :max="currentSession?.remaining || 1" />
        </div>
        <div class="pay-row">
          <span>支付方式</span>
          <el-radio-group v-model="payMethod">
            <el-radio-button :value="1">微信</el-radio-button>
            <el-radio-button :value="2">支付宝</el-radio-button>
            <el-radio-button :value="3">线下</el-radio-button>
          </el-radio-group>
        </div>
      </div>
      <template #footer>
        <el-button @click="payDialog = false">取消</el-button>
        <el-button type="primary" :loading="booking" @click="confirmOrder">确认支付</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head-card {
  margin-bottom: 24px;
  border-radius: var(--radius);
}
.head-row {
  display: flex;
  align-items: flex-start;
  gap: 20px;
}

/* 详情页封面：竖版海报比例 4:5（更贴合剧本封面色调） */
.head-cover {
  flex: 0 0 168px;
  width: 168px;
  height: 210px;
  object-fit: cover;
  border-radius: var(--radius);
  background: #f1f5f9;
}

/* 无封面占位：科技绿渐变 + 首字 */
.head-cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 68px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #0d9488 0%, #06b6d4 100%);
}

.head-main {
  flex: 1;
  min-width: 0;
}

.title-line {
  display: flex;
  align-items: center;
  gap: 10px;
}

.name {
  margin: 0;
  font-size: 22px;
  color: var(--color-text-main);
}

.meta {
  margin: 8px 0 10px;
  color: var(--color-text-sub);
  font-size: 13px;
}

/* 评分行：半星组件 + 数字 + 评价人数 */
.rating-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.rate {
  transform: scale(0.9);
  transform-origin: left center;
}

.mark-num {
  font-size: 16px;
  font-weight: 700;
  color: var(--color-amber);
}

.mark-cnt {
  font-size: 12px;
  color: var(--color-text-sub);
}

.no-rating {
  font-size: 13px;
  color: var(--color-text-sub);
}

/* 性别构成 + 细标签行 */
.gender-line,
.tag-line {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.head-price {
  font-size: 24px;
  font-weight: 700;
  color: var(--color-warning);
  white-space: nowrap;
}

/* ---------- 剧情介绍 ---------- */
.intro-section {
  margin-bottom: 24px;
}

.intro-card {
  border-radius: var(--radius);
}

.intro-text {
  margin: 0;
  font-size: 14px;
  line-height: 1.9;
  color: var(--color-text-main);
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---------- 人物介绍 ---------- */
.char-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}

.char-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px;
  background: #f8fafc;
  border-radius: 12px;
}

/* 角色头像：有图时裁切为圆角方块 */
.char-avatar {
  flex: none;
  width: 52px;
  height: 52px;
  border-radius: 10px;
  object-fit: cover;
  background: #f1f5f9;
}

/* 无图占位：性别色渐变 + 角色名首字 */
.char-avatar-text {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 600;
  color: #fff;
}

.char-avatar-text.male,
.char-gender.male {
  background: linear-gradient(135deg, #06b6d4, #0d9488);
}

.char-avatar-text.female,
.char-gender.female {
  background: linear-gradient(135deg, #ec4899, #f472b6);
}

.char-avatar-text.unknown,
.char-gender.unknown {
  background: linear-gradient(135deg, #94a3b8, #cbd5e1);
}

.char-info {
  min-width: 0;
}

.char-name-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.char-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-main);
}

/* 性别小标签：与头像占位同色渐变 */
.char-gender {
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 12px;
  color: #fff;
}

.char-age {
  font-size: 12px;
  color: var(--color-text-sub);
}

.char-desc {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--color-text-sub);
  line-height: 1.7;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.section-title {
  margin: 0 0 14px;
  font-size: 16px;
  color: var(--color-text-main);
}
.session-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.session-card {
  border-radius: var(--radius);
}
.session-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.s-left {
  flex: 1;
}
.s-date {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-main);
}
.s-meta {
  font-size: 12px;
  color: var(--color-text-sub);
  margin-top: 4px;
}
.s-mid {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 16px;
}
.stock {
  font-size: 13px;
  color: var(--color-text-sub);
  white-space: nowrap;
}

/* ---------- 猜你喜欢 ---------- */
.similar-section {
  margin-top: 26px;
}

.similar-scroll {
  display: flex;
  gap: 14px;
  overflow-x: auto;
  padding: 4px 2px 10px;
  min-height: 200px;
}

.similar-card {
  flex: 0 0 190px;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-soft);
  cursor: pointer;
  overflow: hidden;
  transition: transform 0.2s, box-shadow 0.2s;
}

.similar-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 24px rgba(13, 148, 136, 0.12);
}

.similar-cover {
  width: 100%;
  height: 120px;
  object-fit: cover;
  display: block;
  background: #f1f5f9;
}

/* 无封面占位：科技绿渐变 + 首字 */
.similar-cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 40px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #0d9488 0%, #06b6d4 100%);
}

.similar-info {
  padding: 10px 12px 12px;
}

.similar-name {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.similar-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.similar-mark {
  font-size: 12px;
  color: var(--color-amber);
  white-space: nowrap;
}

.pay-info p {
  margin: 0 0 14px;
  color: var(--color-text-main);
}
.cnt-row,
.pay-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  color: var(--color-text-main);
}
</style>