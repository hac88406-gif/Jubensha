<script setup>
/**
 * 推荐解释 · 关联图谱
 *
 * 目的：解释"为什么推荐这些剧本给你"。
 * 数据源（recommend-service，网关 /api/recommend/** 公开）：
 *   - GET /recommend/graph/explain?limit=  → 你玩过的剧本(played) → 推荐剧本(recs) 的关联链
 *     每条边带 reason：同标签 | 同类型 | 同作者 | 同名角色（跨本同名角色联动）
 *
 * 布局：左列 = 你玩过的剧本（起点），右簇 = 推荐剧本（终点），
 *       连线 = 关联原因（颜色区分），线越粗关联越强。
 * 交互：拖拽 / 缩放 / 点击节点看详情。
 */
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import * as echarts from 'echarts'
import { fetchGraphExplain } from '../../api/recommend'
import { useUserStore } from '../../stores/user'

const userStore = useUserStore()

// ---------------- 状态 ----------------
const loading = ref(false)
const error = ref('')
const limit = ref(6)
const selected = ref(null) // 选中的剧本节点详情
const summary = reactive({ played: 0, recs: 0, links: 0 })

// 剧本类型 → 节点颜色（科技绿主题）
const palette = ['#0d9488', '#06b6d4', '#f59e0b', '#8b5cf6', '#f43f5e', '#64748b']
const typeColor = (type, idx) => palette[idx % palette.length]

// 关联原因 → 边颜色（与页面底部图例一致）
const reasonColor = {
  同标签: '#0d9488',
  同类型: '#06b6d4',
  同作者: '#f59e0b',
  同名角色: '#8b5cf6',
}

// ---------------- 图实例 ----------------
let chart = null
const chartRef = ref(null)

// ---------------- 数据加载 ----------------
async function loadGraph() {
  if (!userStore.isLoggedIn) return
  loading.value = true
  error.value = ''
  try {
    const data = await fetchGraphExplain(limit.value)
    renderGraph(data || {})
  } catch (e) {
    error.value = '推荐图谱加载失败，请确认 recommend-service 与 Neo4j 在线'
  } finally {
    loading.value = false
  }
}

function renderGraph(data) {
  const played = data.played || []
  const recs = data.recs || []
  const links = data.links || []
  if (!chart) return

  summary.played = played.length
  summary.recs = recs.length
  summary.links = links.length

  // 关键：onMounted 时 chart div 是 display:none（summary.played=0），
  // echarts.init 创建的 canvas 是 0×0。nextTick 只保证 Vue 完成 DOM patch，
  // 不保证浏览器完成布局，因此再 defer 一帧 requestAnimationFrame，
  // 确保 v-show 切换为 display:block 后浏览器已算出真实尺寸，再 resize + 渲染。
  nextTick(() => {
    requestAnimationFrame(() => {
      const el = chartRef.value
      const w = el?.clientWidth || 0
      const h = el?.clientHeight || 0
      if (w > 0 && h > 0) {
        chart.resize({ width: w, height: h })
      } else {
        chart.resize()
      }
      doRender(played, recs, links)
    })
  })
}

function doRender(played, recs, links) {
  // 类型 → 颜色：按出现顺序分配（推荐节点着色）
  const types = []
  const colorMap = {}
  recs.forEach((s) => {
    const t = s.scriptType || '未知'
    if (!(t in colorMap)) {
      colorMap[t] = palette[types.length % palette.length]
      types.push(t)
    }
  })

  // 获取画布真实尺寸：直接读 chart div 自身（与 chart.resize 用同一元素）
  // 若 chart div 被 v-show 隐藏导致 clientWidth 为 0，回退到父容器 .graph-panel
  const chartEl = chartRef.value
  const panel = chartEl?.parentElement
  let W = chartEl?.clientWidth || 0
  let H = chartEl?.clientHeight || 0
  if (!W && panel) {
    const panelStyle = getComputedStyle(panel)
    W = panel.clientWidth
      - parseFloat(panelStyle.paddingLeft)
      - parseFloat(panelStyle.paddingRight)
    H = panel.clientHeight
      - parseFloat(panelStyle.paddingTop)
      - parseFloat(panelStyle.paddingBottom)
  }
  const cx = W / 2
  const cy = H / 2

  // ---------- 构建节点（先不带坐标，坐标由布局决定） ----------
  const playedNodes = played.map((s) => ({
    id: String(s.scriptId),
    name: s.name || `剧本${s.scriptId}`,
    value: s.mark,
    category: 0,
    symbol: 'roundRect',
    symbolSize: 28,
    itemStyle: { color: '#134e4a', borderColor: '#0d9488', borderWidth: 2 },
    label: {
      show: true,
      position: 'left',
      color: '#0f766e',
      fontWeight: 600,
      formatter: (p) => (p.name.length > 10 ? p.name.slice(0, 10) + '…' : p.name),
    },
    _data: { ...s, _kind: 'played' },
  }))

  const recNodes = recs.map((s) => {
    const w = Number(s.weight) || 0
    return {
      id: String(s.scriptId),
      name: s.name || `剧本${s.scriptId}`,
      value: s.mark,
      category: 1,
      symbolSize: 26 + Math.min(22, w * 2.5),
      itemStyle: { color: colorMap[s.scriptType || '未知'] },
      label: {
        show: true,
        position: 'right',
        formatter: (p) => (p.name.length > 10 ? p.name.slice(0, 10) + '…' : p.name),
      },
      _data: { ...s, _kind: 'rec' },
    }
  })

  // 边
  const edges = links.map((l, idx) => ({
    source: String(l.source),
    target: String(l.target),
    value: l.weight,
    reason: l.reason,
    lineStyle: {
      color: reasonColor[l.reason] || '#94a3b8',
      width: Math.min(3.5, 0.6 + (Number(l.weight) || 0) * 0.5),
      opacity: 0.45,
      curveness: 0.15 + ((idx % 5) * 0.04),
    },
  }))

  // ---------- 径向分层布局：中心"我" → 内圈玩过 → 外圈推荐 ----------
  const centerNode = {
    id: 'me',
    name: '我',
    category: 2,
    symbol: 'circle',
    symbolSize: 52,
    x: cx,
    y: cy,
    itemStyle: { color: '#0d9488', borderColor: '#fff', borderWidth: 3, shadowBlur: 12, shadowColor: 'rgba(13,148,136,.4)' },
    label: { show: true, color: '#fff', fontWeight: 700, fontSize: 14 },
    _data: { name: '我', _kind: 'me' },
  }
  const innerR = Math.min(W, H) * 0.22
  const outerR = Math.min(W, H) * 0.4
  playedNodes.forEach((n, i) => {
    const angle = (2 * Math.PI * i) / Math.max(playedNodes.length, 1) - Math.PI / 2
    n.x = cx + innerR * Math.cos(angle)
    n.y = cy + innerR * Math.sin(angle)
    n.label.position = 'outside'
  })
  recNodes.forEach((n, i) => {
    const angle = (2 * Math.PI * i) / Math.max(recNodes.length, 1) - Math.PI / 2
    n.x = cx + outerR * Math.cos(angle)
    n.y = cy + outerR * Math.sin(angle)
    n.label.position = 'outside'
  })
  // 中心"我"连到每个玩过的剧本
  const meEdges = playedNodes.map((n) => ({
    source: 'me',
    target: n.id,
    value: 0,
    reason: '我玩过',
    lineStyle: { color: '#0d9488', width: 1.5, opacity: 0.35, type: 'dashed' },
  }))
  const nodes = [centerNode, ...playedNodes, ...recNodes]
  edges.unshift(...meEdges)

  chart.setOption({
    tooltip: {
      formatter: (p) => {
        if (p.dataType === 'edge') {
          return `<b>${p.data.reason}</b><br/>关联强度 ${p.value ?? '?'}`
        }
        const s = p.data?._data
        if (!s) return p.name
        if (s._kind === 'me') return '🎯 你'
        const head = s._kind === 'played' ? '🎭 你玩过' : '⭐ 推荐给你'
        return `<b>${head} · 《${s.name}》</b><br/>类型：${s.scriptType || '未知'}${
          s.mark != null ? `<br/>评分：${s.mark}` : ''
        }`
      },
      backgroundColor: '#0f172a',
      textStyle: { color: '#e2e8f0' },
      extraCssText: 'border-radius:10px;box-shadow:0 8px 24px rgba(13,148,136,.15);',
    },
    legend: {
      data: [
        { name: '你玩过的剧本', icon: 'roundRect', itemStyle: { color: '#134e4a' } },
        ...types.map((t) => ({ name: `推荐 · ${t}`, icon: 'circle', itemStyle: { color: colorMap[t] } })),
      ],
      top: 8,
      left: 16,
      textStyle: { color: '#64748b', fontSize: 12 },
    },
    series: [
      {
        type: 'graph',
        layout: 'none', // 径向分层用手动坐标
        data: nodes,
        links: edges,
        categories: [
          { name: '你玩过的剧本' },
          { name: '推荐' },
          { name: '我' },
        ],
        roam: true,
        draggable: true,
        label: { color: '#334155', fontSize: 11 },
        edgeSymbol: ['none', 'arrow'],
        edgeLabel: {
          show: false,
          formatter: (p) => p.data?.reason || '',
          color: '#0f172a',
          fontSize: 11,
          backgroundColor: '#fff',
          padding: [2, 6],
          borderRadius: 4,
        },
        emphasis: {
          focus: 'adjacency',
          lineStyle: { width: 4, opacity: 0.95 },
          label: { color: '#0d9488', fontWeight: 700 },
          edgeLabel: { show: true },
        },
      },
    ],
  }, true)
}

// ---------------- 交互 ----------------
function handleChartClick(params) {
  if (params.dataType !== 'node') return
  const s = params.data?._data
  // 中心"我"节点不进详情面板
  if (s && s._kind !== 'me') selected.value = s
}

function changeLimit(v) {
  limit.value = v
  loadGraph()
}

// ---------------- 生命周期 ----------------
let resizeTimer = null
function resizeChart() {
  // 手动布局的 x/y 依赖画布尺寸，resize 后需重渲染
  clearTimeout(resizeTimer)
  resizeTimer = setTimeout(() => {
    chart?.resize()
    loadGraph()
  }, 150)
}

onMounted(async () => {
  chart = echarts.init(chartRef.value)
  chart.on('click', handleChartClick)
  window.addEventListener('resize', resizeChart)
  await loadGraph()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="kg-page">
    <div class="head">
      <div>
        <h2 class="u-page-title">推荐解释 · 关联图谱</h2>
        <p class="u-page-sub">
          为什么推荐这些剧本给你？从你玩过的剧本出发，沿「同标签 / 同类型 / 同作者 / 同名角色」关系找到的推荐路径
        </p>
      </div>
      <div class="head-actions">
        <el-radio-group :model-value="limit" size="small" @update:model-value="changeLimit">
          <el-radio-button :value="6">6 本</el-radio-button>
          <el-radio-button :value="10">10 本</el-radio-button>
          <el-radio-button :value="15">15 本</el-radio-button>
        </el-radio-group>
        <el-button size="small" type="primary" :loading="loading" @click="loadGraph">
          刷新
        </el-button>
      </div>
    </div>

    <!-- 未登录提示 -->
    <div v-if="!userStore.isLoggedIn" class="u-card login-tip">
      <p>👆 登录后，这里会展示「你玩过的剧本 → 为什么推荐这些新剧本」的关联路径。</p>
      <el-button size="small" type="primary" plain @click="$router.push('/login')">去登录</el-button>
    </div>

    <template v-else>
      <!-- 概览摘要 -->
      <div class="stats-grid">
        <div class="stat-card">
          <span class="stat-icon">🎭</span>
          <div class="stat-body">
            <div class="stat-num">{{ summary.played }}</div>
            <div class="stat-label">你玩过的剧本</div>
          </div>
        </div>
        <div class="stat-card">
          <span class="stat-icon">⭐</span>
          <div class="stat-body">
            <div class="stat-num">{{ summary.recs }}</div>
            <div class="stat-label">推荐给你的剧本</div>
          </div>
        </div>
        <div class="stat-card">
          <span class="stat-icon">🔗</span>
          <div class="stat-body">
            <div class="stat-num">{{ summary.links }}</div>
            <div class="stat-label">关联路径</div>
          </div>
        </div>
      </div>

      <!-- 图谱主体：左图 + 右详情 -->
      <div class="graph-wrap">
        <div class="graph-panel u-card">
          <!-- 边颜色图例（放在图表上方，避免浮层遮挡节点） -->
          <div class="edge-legend">
            <span class="legend-title">关联原因：</span>
            <span v-for="(color, reason) in reasonColor" :key="reason" class="legend-item">
              <i class="dot" :style="{ background: color }" />{{ reason }}
            </span>
          </div>
          <div v-if="loading" class="graph-loading">
            <el-icon class="is-loading" :size="28"><i class="el-icon-loading" /></el-icon>
            <span>图谱加载中…</span>
          </div>
          <div v-else-if="error" class="graph-error">
            <p>{{ error }}</p>
            <el-button size="small" type="primary" @click="loadGraph">重试</el-button>
          </div>
          <div v-else-if="!summary.played" class="graph-error">
            <p>你还没有玩过任何剧本<br />去 <router-link to="/player/hall" class="link">剧本大厅</router-link> 下一单体验一本吧</p>
          </div>
          <div ref="chartRef" v-show="!loading && !error && summary.played" class="chart" />
        </div>

        <!-- 详情面板 -->
        <aside class="detail-panel u-card">
          <template v-if="selected">
            <img v-if="selected.image" :src="selected.image" class="detail-cover" alt="封面" />
            <div v-else class="detail-cover detail-cover-empty">📜</div>
            <div class="detail-kind" :class="selected._kind === 'played' ? 'kind-played' : 'kind-rec'">
              {{ selected._kind === 'played' ? '🎭 你玩过的' : '⭐ 推荐给你' }}
            </div>
            <h3 class="detail-name">《{{ selected.name }}》</h3>
            <div class="detail-meta">
              <el-tag size="small" effect="plain" type="success">{{ selected.scriptType || '未知' }}</el-tag>
              <el-tag v-if="selected.mark != null" size="small" effect="dark" type="warning">
                ⭐ {{ selected.mark }}
              </el-tag>
            </div>
            <dl class="detail-list">
              <div v-if="selected._kind === 'rec'">
                <dt>关联强度</dt>
                <dd>{{ selected.weight ?? 0 }}</dd>
              </div>
            </dl>
          </template>
          <div v-else class="detail-empty">
            <span>👆</span>
            <p>点击图谱中的节点<br />查看详情</p>
          </div>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.kg-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.login-tip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  color: var(--color-text-sub);
  font-size: 13px;
}

.link {
  color: var(--color-emerald);
  text-decoration: none;
}

/* ---------- 摘要卡片 ---------- */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-soft);
}

.stat-icon {
  font-size: 22px;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: rgba(13, 148, 136, 0.08);
}

.stat-num {
  font-size: 20px;
  font-weight: 700;
  color: var(--color-emerald);
  line-height: 1.2;
}

.stat-label {
  font-size: 12px;
  color: var(--color-text-sub);
}

/* ---------- 图谱主体 ---------- */
.graph-wrap {
  display: grid;
  grid-template-columns: 1fr 260px;
  gap: 16px;
  align-items: stretch;
}

.graph-panel {
  position: relative;
  min-height: 640px;
  padding: 12px;
  box-sizing: border-box;
}

.chart {
  width: 100%;
  height: 620px;
}

.graph-loading,
.graph-error {
  height: 620px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--color-text-sub);
  font-size: 13px;
  text-align: center;
  line-height: 1.8;
}

/* 边颜色图例（图表上方，正常流式，不遮挡节点） */
.edge-legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: var(--color-text-sub);
  padding: 4px 4px 10px;
}

.legend-title {
  font-weight: 600;
  color: var(--color-text-main);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.legend-item .dot {
  width: 18px;
  height: 4px;
  border-radius: 2px;
  display: inline-block;
}

/* ---------- 详情面板 ---------- */
.detail-panel {
  padding: 16px;
  box-sizing: border-box;
  min-height: 520px;
  max-height: 560px;
  overflow-y: auto;
}

.detail-cover {
  width: 100%;
  height: 180px;
  object-fit: cover;
  border-radius: 12px;
  background: #f1f5f9;
}

.detail-cover-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 44px;
  background: linear-gradient(135deg, rgba(13, 148, 136, 0.08), rgba(6, 182, 212, 0.08));
}

.detail-kind {
  display: inline-block;
  margin-top: 12px;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
}

.kind-played {
  background: rgba(13, 148, 136, 0.1);
  color: #0f766e;
}

.kind-rec {
  background: rgba(245, 158, 11, 0.12);
  color: #b45309;
}

.detail-name {
  margin: 8px 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-main);
}

.detail-meta {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.detail-list {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.detail-list > div {
  display: flex;
  gap: 10px;
  font-size: 13px;
}

.detail-list dt {
  color: var(--color-text-sub);
  min-width: 42px;
  flex-shrink: 0;
}

.detail-list dd {
  margin: 0;
  color: var(--color-text-main);
  line-height: 1.6;
}

.detail-empty {
  height: 100%;
  min-height: 420px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--color-text-sub);
  font-size: 14px;
  text-align: center;
  line-height: 1.8;
}

.detail-empty span {
  font-size: 40px;
  margin-bottom: 8px;
}

@media (max-width: 900px) {
  .graph-wrap {
    grid-template-columns: 1fr;
  }
}
</style>
