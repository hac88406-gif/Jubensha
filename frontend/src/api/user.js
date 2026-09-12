import service from './http'

/**
 * 用户认证 API（user-service，经网关 /api/user/**）
 */
export const userApi = {
  /** 注册：ROLE_PLAYER / ROLE_SHOP_OWNER 可选 */
  register(data) {
    return service.post('/user/register', data)
  },

  /** 登录：返回 { token, userId, role, nickname } */
  login(data) {
    return service.post('/user/login', data)
  },

  /** 个人信息（需登录，Gateway 注入 X-User-Id） */
  profile() {
    return service.get('/user/profile')
  },
}

export default userApi