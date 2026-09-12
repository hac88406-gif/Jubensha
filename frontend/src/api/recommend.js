import http from './http'

/**
 * 推荐 API（走网关 /api/** → StripPrefix=1 → recommend-service）
 *
 * 契约（后端 RecommendController 已核对）：
 *   GET /api/recommend                个性化推荐（登录自动带 X-User-Id；未登录回退热门 10 条）
 *   GET /api/recommend/hot?type=      热门榜（可选按类型过滤，最多 20 条）
 *   GET /api/recommend/similar/{id}   相似剧本 · 猜你喜欢（6 条）
 *
 * 返回元素字段（Neo4j 图数据）：
 *   scriptId / name / image / mark(评分, 可能为 null) / scriptType / score
 * 注意：推荐接口不返回 人数/时长/价格 等详情字段，卡片展示需做容错。
 */

/** 个性化推荐（登录用户；未登录时后端自动返回热门） */
export function fetchRecommend() {
  return http.get('/recommend')
}

/** 热门推荐（可选按类型过滤） */
export function fetchHot(type) {
  return http.get('/recommend/hot', { params: { type: type || '' } })
}

/** 相似剧本（剧本详情页「猜你喜欢」） */
export function fetchSimilar(scriptId) {
  return http.get(`/recommend/similar/${scriptId}`)
}

/**
 * 图谱统计（P2 可视化 · 概览卡片）
 *
 * 后端 RecommendController /recommend/stats → GraphSyncService.stats()
 * 返回节点/关系计数：
 *   scripts / users / tags / types / authors / characters
 *   relatedRel / sameNameRel / coCharacterRel / playedRel
 */
export function fetchGraphStats() {
  return http.get('/recommend/stats')
}

/**
 * 图谱快照（P2 可视化 · 关系图）
 *
 * 后端 RecommendController /recommend/graph/snapshot?limit= → GraphSyncService.graphSnapshot()
 * 返回 {"scripts": [{scriptId,name,scriptType,mark,image,price,tags,types,authors,charCount}],
 *        "links":  [{source,target,weight}]}   —— source/target 为 scriptId
 */
export function fetchGraphSnapshot(limit = 30) {
  return http.get('/recommend/graph/snapshot', { params: { limit } })
}

/**
 * 推荐解释图（P2 可视化 · 推荐解释）
 *
 * 后端 RecommendController /recommend/graph/explain?limit= → RecommendService.graphExplain()
 * 登录自动带 X-User-Id；返回"用户玩过的剧本 → 推荐剧本"的关联链：
 *   {"played": [{scriptId,name,scriptType,mark,image}],
 *    "recs":   [{scriptId,name,scriptType,mark,image,weight}],
 *    "links":  [{source,target,reason,weight}]}
 *   reason ∈ 同标签 | 同类型 | 同作者 | 同名角色；未登录返回空图
 */
export function fetchGraphExplain(limit = 10) {
  return http.get('/recommend/graph/explain', { params: { limit } })
}