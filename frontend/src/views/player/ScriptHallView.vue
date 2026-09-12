<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { fetchScriptList, fetchShopList, fetchSessionsByShop } from '../../api/shop'
import { fetchRecommend } from '../../api/recommend'

/**
 * 剧本大厅 —— 玩家端首页
 * 顶部「为你推荐」：登录用户个性化推荐（后端按知识图谱召回），未登录自动回退热门榜。
 * 下方剧本卡片列表：封面图 + 评分 + 细标签过滤，支持按 关键词 / 人数 / 类型 / 标签 筛选。
 * 排序策略：有可约场次的剧本排前面（玩家点进去就能下单），无场次的沉底。
 * 点击卡片 → 剧本详情（含场次与下单）。
 */
const router = useRouter()

const loading = ref(false)
const scripts = ref([])

// 「为你推荐」区块：登录个性化 / 未登录热门，后端统一由 /api/recommend 返回
const recs = ref([])
const recLoading = ref(false)

// 筛选条件（对齐后端 /script/list 参数）
const filters = ref({
  nameKeyword: '',
  playerCnt: null,
  type: '',
})

// 剧本类型（后端 ScriptQueryReq 注释：硬核/情感/欢乐/机制）
const scriptTypes = ['硬核', '情感', '欢乐', '机制']

// 细标签：从全量剧本数据中动态收集去重（tags 为单一标签，如 推理/解谜/还原/欢乐/情感/机制）
const tagOptions = ref([])
const selectedTag = ref('')

// 展示列表：在「可约置顶」排序基础上叠加细标签过滤（本地过滤，不重复请求后端）
const displayList = computed(() => {
  if (!selectedTag.value) return scripts.value
  return scripts.value.filter((s) => s.tags === selectedTag.value)
})

/**
 * 收集"未来 7 天有可约场次"的剧本 id 集合。
 * 通过门店场次接口一次性拉全（比按剧本逐个查省很多请求）。
 * @returns {Promise<Set<number>>} 有可约场次剧本的 id 集合
 */
async function collectBookableScriptIds() {
  const bookable = new Set()
  let shops = []
  try {
    const shopData = await fetchShopList()
    shops = Array.isArray(shopData) ? shopData : []
  } catch (e) {
    console.warn('[Hall] 拉取门店列表失败，跳过可约筛选', e)
    // 门店都拿不到则不排序，直接返回空集合（退化为默认顺序）
    return bookable
  }
  // 并行拉取每家门店未来 7 天场次，收集有场次(且可约)的剧本 id
  await Promise.all(
    shops.map(async (shop) => {
      try {
        const sessions = await fetchSessionsByShop(shop.id)
        if (!Array.isArray(sessions)) return
        for (const s of sessions) {
          // 可约判定：场次开放(status=1) 且 有余位
          if (s.status === 1 && s.remaining > 0) bookable.add(s.scriptId)
        }
      } catch (e) {
        console.warn(`[Hall] 门店 ${shop.id} 场次拉取失败，跳过`, e)
      }
    }),
  )
  return bookable
}

/**
 * 加载「为你推荐」：登录用户由网关注入 X-User-Id → 个性化推荐；
 * 未登录后端自动回退热门榜。失败静默（只影响推荐区，不影响剧本列表）。
 */
async function loadRecs() {
  recLoading.value = true
  try {
    const data = await fetchRecommend()
    recs.value = Array.isArray(data) ? data : []
  } catch (e) {
    console.warn('[Hall] 推荐拉取失败，隐藏推荐区', e)
    recs.value = []
  } finally {
    recLoading.value = false
  }
}

// 评分展示：mark 可能为 null（无评分的冷门剧本），此时显示占位符
function fmtMark(v) {
  return v === null || v === undefined ? '暂无' : `评分 ${v}`
}

async function load() {
  loading.value = true
  try {
    const params = {}
    if (filters.value.nameKeyword?.trim()) params.nameKeyword = filters.value.nameKeyword.trim()
    if (filters.value.playerCnt) params.playerCnt = filters.value.playerCnt
    if (filters.value.type) params.type = filters.value.type
    const data = await fetchScriptList(params)
    const list = Array.isArray(data) ? data : []

    // 有可约场次的剧本排前面
    const bookable = await collectBookableScriptIds()
    for (const s of list) s.bookable = bookable.has(s.id)
    list.sort((a, b) => {
      // 可约在前；同组内保持后端返回顺序稳定
      if (a.bookable !== b.bookable) return a.bookable ? -1 : 1
      return 0
    })

    scripts.value = list
    // 细标签选项：从全量数据收集去重（无 tags 的剧本不产生选项）
    tagOptions.value = [...new Set(list.map((s) => s.tags).filter(Boolean))].sort()
  } catch (e) {
    scripts.value = []
  } finally {
    loading.value = false
  }
}

function doSearch() {
  load()
}

function resetSearch() {
  filters.value = { nameKeyword: '', playerCnt: null, type: '' }
  selectedTag.value = ''
  load()
}

function goDetail(id) {
  router.push(`/player/script/${id}`)
}

// 价格展示：元
function fmtPrice(v) {
  if (v === null || v === undefined) return '-'
  return `¥${v}`
}

onMounted(() => {
  load()
  loadRecs()
})
</script>

<template>
  <div class="script-hall">
    <!-- 「为你推荐」：登录个性化 / 未登录热门；无数据或失败时整块隐藏 -->
    <div v-if="recLoading || recs.length > 0" class="rec-section">
      <div class="rec-head">
        <h3 class="rec-title">⚡ 为你推荐</h3>
        <span class="rec-desc">基于你的体验记录，智能匹配好本</span>
      </div>
      <div v-loading="recLoading" class="rec-scroll">
        <div
          v-for="r in recs"
          :key="r.scriptId"
          class="rec-card"
          @click="goDetail(r.scriptId)"
        >
          <!-- 封面：推荐接口有 image 用图，否则渐变占位块 + 剧本名首字 -->
          <img v-if="r.image" :src="r.image" class="rec-cover" alt="" />
          <div v-else class="rec-cover rec-cover-placeholder">{{ (r.name || '?').slice(0, 1) }}</div>
          <div class="rec-info">
            <p class="rec-name">{{ r.name }}</p>
            <div class="rec-foot">
              <el-tag size="small" type="info" effect="plain">{{ r.scriptType }}</el-tag>
              <span class="rec-mark">{{ fmtMark(r.mark) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 筛选栏 -->
    <el-card shadow="never" class="filter-card">
      <div class="filter-row">
        <el-input
          v-model="filters.nameKeyword"
          placeholder="搜索剧本名称"
          clearable
          style="width: 220px"
          @keyup.enter="doSearch"
        >
          <template #prefix><span class="search-icon">⌕</span></template>
        </el-input>
        <el-select v-model="filters.playerCnt" placeholder="适合人数" clearable style="width: 140px">
          <el-option v-for="n in [2, 4, 6, 8, 10]" :key="n" :value="n" :label="`${n} 人`" />
        </el-select>
        <el-select v-model="filters.type" placeholder="剧本类型" clearable style="width: 140px">
          <el-option v-for="t in scriptTypes" :key="t" :value="t" :label="t" />
        </el-select>
        <el-select v-model="selectedTag" placeholder="细标签" clearable style="width: 140px">
          <el-option v-for="t in tagOptions" :key="t" :value="t" :label="t" />
        </el-select>
        <el-button type="primary" @click="doSearch">查询</el-button>
        <el-button @click="resetSearch">重置</el-button>
        <span class="total-tip">共 {{ displayList.length }} 个剧本</span>
      </div>
    </el-card>

    <!-- 剧本卡片列表 -->
    <div v-loading="loading" class="script-grid">
      <el-empty v-if="!loading && displayList.length === 0" description="没有符合条件的剧本，换个筛选试试" />
      <el-card
        v-for="s in displayList"
        :key="s.id"
        shadow="hover"
        class="script-card"
        @click="goDetail(s.id)"
      >
        <!-- 封面：有 image 用图，否则渐变占位块 + 剧本名首字 -->
        <img v-if="s.image" :src="s.image" class="script-cover" alt="" />
        <div v-else class="script-cover script-cover-placeholder">{{ (s.name || '?').slice(0, 1) }}</div>
        <div class="card-body">
          <div class="card-head">
            <el-tag size="small" type="info" effect="plain">{{ s.scriptType }}</el-tag>
            <el-tag v-if="s.bookable" size="small" type="success" effect="light">可约</el-tag>
            <el-tag v-else size="small" type="warning" effect="plain">暂无场次</el-tag>
          </div>
          <h3 class="script-name">{{ s.name }}</h3>
          <p class="script-meta">
            <span v-if="s.mark !== null && s.mark !== undefined" class="rating">
              <span class="star">★</span> {{ s.mark }}
            </span>
            <span v-else class="no-rating">暂无评分</span>
            · {{ s.playerMin }}-{{ s.playerMax }} 人 · {{ s.duration }} 分钟
          </p>
          <!-- 细标签文本：与详情页一致的醒目样式，无标签的剧本不占位 -->
          <div v-if="s.tags" class="card-tags">
            <el-tag size="small" type="warning" effect="light">{{ s.tags }}</el-tag>
          </div>
          <div class="card-foot">
            <span class="price">{{ fmtPrice(s.price) }}</span>
            <span class="author">作者：{{ s.author || '-' }}</span>
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.rec-section {
  margin-bottom: 20px;
}

.rec-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;
}

.rec-title {
  margin: 0;
  font-size: 17px;
  color: var(--color-text-main);
}

.rec-desc {
  font-size: 12px;
  color: var(--color-text-sub);
}

/* 推荐横滑条：宽度超出时横向滚动 */
.rec-scroll {
  display: flex;
  gap: 14px;
  overflow-x: auto;
  padding: 4px 2px 10px;
  min-height: 210px;
}

.rec-card {
  flex: 0 0 200px;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-soft);
  cursor: pointer;
  overflow: hidden;
  transition: transform 0.2s, box-shadow 0.2s;
}

.rec-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 24px rgba(13, 148, 136, 0.12);
}

.rec-cover {
  width: 100%;
  height: 128px;
  object-fit: cover;
  display: block;
  background: #f1f5f9;
}

/* 无封面占位：科技绿渐变 + 首字 */
.rec-cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 42px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #0d9488 0%, #06b6d4 100%);
}

.rec-info {
  padding: 10px 12px 12px;
}

.rec-name {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.rec-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.rec-mark {
  font-size: 12px;
  color: var(--color-amber);
  white-space: nowrap;
}

.filter-card {
  margin-bottom: 20px;
  border-radius: var(--radius);
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.total-tip {
  margin-left: auto;
  color: var(--color-text-sub);
  font-size: 13px;
}

.search-icon {
  font-size: 16px;
  color: var(--color-text-sub);
}

.script-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
  min-height: 200px;
}

.script-card {
  cursor: pointer;
  border-radius: var(--radius);
  overflow: hidden;
  transition: transform 0.2s, box-shadow 0.2s;
}

.script-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 24px rgba(13, 148, 136, 0.12);
}

/* 卡片封面：宽高比固定 3:2，裁切不失真 */
.script-cover {
  width: 100%;
  height: 158px;
  object-fit: cover;
  display: block;
  background: #f1f5f9;
}

/* 无封面占位：科技绿渐变 + 首字 */
.script-cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 52px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #0d9488 0%, #06b6d4 100%);
}

/* 信息区统一的左右内边距（封面通栏） */
.script-card :deep(.el-card__body) {
  padding: 0;
}

.card-body {
  padding: 14px 16px 16px;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.offline-tag {
  font-size: 12px;
  color: var(--color-text-sub);
}

.script-name {
  margin: 12px 0 8px;
  font-size: 17px;
  color: var(--color-text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.script-meta {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--color-text-sub);
}

/* 细标签行：与详情页 tag-line 一致的紧凑间距 */
.card-tags {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

/* 评分：琥珀色星 + 数字，无评分时灰色占位 */
.rating {
  color: var(--color-amber);
  font-weight: 600;
}

.star {
  color: var(--color-amber);
}

.no-rating {
  color: var(--color-text-sub);
}

.card-foot {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.price {
  font-size: 18px;
  font-weight: 700;
  color: var(--color-warning);
}

.author {
  font-size: 12px;
  color: var(--color-text-sub);
}
</style>