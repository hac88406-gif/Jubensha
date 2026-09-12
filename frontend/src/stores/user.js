import { defineStore } from 'pinia'

/**
 * 用户状态 store
 *
 * 轻量设计：只存 token / userId / role / nickname，
 * 持久化到 localStorage，刷新页面不丢登录态。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    userId: Number(localStorage.getItem('userId')) || null,
    role: localStorage.getItem('role') || '',
    nickname: localStorage.getItem('nickname') || '',
  }),

  getters: {
    /** 是否已登录 */
    isLoggedIn: (s) => !!s.token,
    /** 是否管理端角色（仅店长） */
    isAdminSide: (s) => s.role === 'ROLE_SHOP_OWNER',
    /** 是否玩家端角色（玩家；DM 等其余非管理端角色也并入玩家端） */
    isPlayer: (s) => s.role !== 'ROLE_SHOP_OWNER',
  },

  actions: {
    /** 登录成功后写入并持久化 */
    setLogin({ token, userId, role, nickname }) {
      this.token = token
      this.userId = userId
      this.role = role
      this.nickname = nickname
      localStorage.setItem('token', token)
      localStorage.setItem('userId', String(userId))
      localStorage.setItem('role', role)
      localStorage.setItem('nickname', nickname || '')
    },

    /** 退出登录：清内存 + 清 localStorage */
    logout() {
      this.token = ''
      this.userId = null
      this.role = ''
      this.nickname = ''
      localStorage.removeItem('token')
      localStorage.removeItem('userId')
      localStorage.removeItem('role')
      localStorage.removeItem('nickname')
    },
  },
})