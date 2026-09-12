import http from './http'

/**
 * 剧本 / 场次 / 门店 API（走网关 /api/** → StripPrefix=1 → 服务 Controller）
 *
 * 契约（后端已核对）：
 *   GET  /api/script/list             剧本列表（公开）
 *   GET  /api/script/{id}             剧本详情（公开）
 *   GET  /api/session/list/script/{id} 场次列表-按剧本（公开）
 *   GET  /api/session/list/shop/{id}   场次列表-按门店（公开）
 *   GET  /api/shop/list               门店列表（公开）
 */

/** 剧本列表。params: { shopId?, type?, playerCnt?, nameKeyword? } */
export function fetchScriptList(params = {}) {
  return http.get('/script/list', { params })
}

/** 剧本详情 */
export function fetchScriptDetail(id) {
  return http.get(`/script/${id}`)
}

/** 按剧本查场次（返回 SessionRes[]） */
export function fetchSessionsByScript(scriptId) {
  return http.get(`/session/list/script/${scriptId}`)
}

/** 按门店查场次 */
export function fetchSessionsByShop(shopId) {
  return http.get(`/session/list/shop/${shopId}`)
}

/** 门店列表 */
export function fetchShopList() {
  return http.get('/shop/list')
}