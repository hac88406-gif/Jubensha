import http from './http'

/**
 * 订单 / 支付 API（玩家端）
 *
 * 契约（后端已核对）：
 *   POST   /api/order                 下单 → 返回 orderNo（字符串）
 *   GET    /api/order/mine            我的订单列表 → OrderRes[]
 *   GET    /api/order/{id}            订单详情（id 为订单表主键）
 *   DELETE /api/order/{id}            取消订单（id 为订单表主键）
 *   POST   /api/order/pay/prepay      发起支付 { orderNo, payMethod } → PrepayRes
 *   POST   /api/order/pay/notify      模拟支付回调（无鉴权，靠 HMAC 签名）
 */

/** 下单。req: { sessionId, playerCnt } */
export function createOrder(req) {
  return http.post('/order', req)
}

/** 我的订单 */
export function fetchMyOrders() {
  return http.get('/order/mine')
}

/** 订单详情 */
export function fetchOrderDetail(id) {
  return http.get(`/order/${id}`)
}

/** 取消订单 */
export function cancelOrder(id) {
  return http.delete(`/order/${id}`)
}

/**
 * 发起支付。
 * req: { orderNo, payMethod }  payMethod: 1-微信 2-支付宝 3-线下
 * 返回 PrepayRes: { paymentNo, orderNo, amount, payMethod, channel, sign, payUrl }
 */
export function prepay(req) {
  return http.post('/order/pay/prepay', req)
}

/**
 * 模拟支付回调（前端演示用：模拟"用户已支付"，支付平台异步回调）。
 * 注意：notify 不需要带 token，靠 sign 验签。
 * req: { paymentNo, orderNo, amount, channel, sign } —— sign 直接回传 prepay 返回的 sign
 */
export function payNotify(req) {
  return http.post('/order/pay/notify', req)
}