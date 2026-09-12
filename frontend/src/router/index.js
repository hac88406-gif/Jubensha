import { createRouter, createWebHashHistory } from 'vue-router'
import { ElMessage } from 'element-plus'

/**
 * 路由表 + 全局守卫
 *
 * 权限约定（对齐后端）：
 *   - /login         公开，未登录可访问
 *   - /player/**     ROLE_PLAYER
 *   - /admin/**      ROLE_SHOP_OWNER
 *   - /agent         ROLE_PLAYER（AI 陪练）
 *
 * 守卫只做「导航对齐」，真正鉴权永远在后端（Gateway JWT + @RequireRole）。
 */
const routes = [
  {
    path: '/',
    // 根路径不能写死跳 /player（admin 刷新首屏会被守卫拦）。
    // 改成按角色分流：管理端 → /admin/sessions，其余 → /player；守卫会再拦未登录 → /login
    redirect: () => {
      const role = localStorage.getItem('role') || ''
      return role === 'ROLE_SHOP_OWNER' ? '/admin/sessions' : '/player'
    },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/player',
    component: () => import('../layouts/PlayerLayout.vue'),
    redirect: '/player/scripts',
    // 玩家端：ROLE_PLAYER 为主；DM 等非管理端角色也并入（后端 @RequireRole 兜底业务权限）
    meta: { roles: ['ROLE_PLAYER', 'ROLE_DM'] },
    children: [
      {
        path: 'scripts',
        name: 'player-scripts',
        component: () => import('../views/player/ScriptHallView.vue'),
        meta: { title: '剧本大厅' },
      },
      {
        path: 'script/:id',
        name: 'player-script-detail',
        component: () => import('../views/player/ScriptDetailView.vue'),
        meta: { title: '剧本详情' },
      },
      {
        path: 'orders',
        name: 'player-orders',
        component: () => import('../views/player/MyOrdersView.vue'),
        meta: { title: '我的订单' },
      },
      {
        path: 'knowledge-graph',
        name: 'player-knowledge-graph',
        component: () => import('../views/player/KnowledgeGraphView.vue'),
        meta: { title: '知识图谱' },
      },
    ],
  },
  {
    path: '/admin',
    component: () => import('../layouts/AdminLayout.vue'),
    redirect: '/admin/sessions',
    meta: { roles: ['ROLE_SHOP_OWNER'] },
    children: [
      {
        path: 'sessions',
        name: 'admin-sessions',
        component: () => import('../views/PlaceholderView.vue'),
        meta: { title: '场次管理', placeholder: '管理端 · 场次管理（S4 实现）' },
      },
      {
        path: 'scripts',
        name: 'admin-scripts',
        component: () => import('../views/PlaceholderView.vue'),
        meta: { title: '剧本管理', placeholder: '管理端 · 剧本管理（S4 实现）' },
      },
      {
        path: 'shops',
        name: 'admin-shops',
        component: () => import('../views/PlaceholderView.vue'),
        meta: { title: '门店管理', placeholder: '管理端 · 门店管理（S4 实现）' },
      },
      {
        path: 'orders',
        name: 'admin-orders',
        component: () => import('../views/PlaceholderView.vue'),
        meta: { title: '订单监控', placeholder: '管理端 · 订单监控（S4 实现）' },
      },
    ],
  },
  {
    path: '/agent',
    name: 'agent',
    component: () => import('../layouts/PlayerLayout.vue'),
    // AI 陪练：非管理端角色（玩家 / DM 等）均可访问，与后端 @RequireRole 对齐
    meta: { roles: ['ROLE_PLAYER', 'ROLE_DM'] },
    children: [
      {
        path: '',
        name: 'agent-chat',
        component: () => import('../views/player/AgentChatView.vue'),
        meta: { title: 'AI 陪练' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/player',
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

// 设置页面标题
router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 剧本杀预约平台` : '剧本杀预约平台'
})

// ---------- 全局前置守卫 ----------
// 权限模型：只分「管理端 / 非管理端」两类：
//   管理端 = ROLE_SHOP_OWNER（店长，唯一管理角色）→ 只进 /admin/**
//   非管理端（玩家 / DM 等其余所有角色）→ 只进 /player/（业务权限由后端 @RequireRole 兜底）
router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  const role = localStorage.getItem('role') || ''
  const isAdminSide = role === 'ROLE_SHOP_OWNER'

  // 已登录访问登录页 → 直接回首页（按是否管理端分流）
  if (token && to.path === '/login') {
    return isAdminSide ? '/admin/sessions' : '/player'
  }

  // 未登录 + 非公开页 → 去登录（记录来源，登录后跳回）
  if (!token && !to.meta.public) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 已登录但角色不符（如玩家强闯 /admin）→ 提示并送回家
  if (token && to.meta.roles && !to.meta.roles.includes(role)) {
    ElMessage.warning('当前账号无权访问该页面')
    // ⚠️ 防自循环：如果目标就是 home 但角色不符（如脏 role 残留），
    //    直接去登录页清掉脏 token，而不是 return 同一个路径导致无限重定向。
    const home = isAdminSide ? '/admin/sessions' : '/player'
    if (to.path === home) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    return home
  }

  return true
})

export default router