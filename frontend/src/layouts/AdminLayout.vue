<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'

const user = useUserStore()
const route = useRoute()
const router = useRouter()

// 管理端侧边栏菜单（店长/管理员共用）
const menus = [
  { path: '/admin/sessions', label: '场次管理', icon: '📅' },
  { path: '/admin/scripts', label: '剧本管理', icon: '📜' },
  { path: '/admin/shops', label: '门店管理', icon: '🏠' },
  { path: '/admin/orders', label: '订单监控', icon: '🧾' },
]

/** 当前激活菜单（高亮） */
const activeMenu = computed(() => {
  const m = menus.find((x) => route.path.startsWith(x.path))
  return m ? m.path : menus[0].path
})

function logout() {
  user.logout()
  router.push('/login')
}
</script>

<template>
  <div class="admin-layout">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-dot" />
        <span>预约管理台</span>
      </div>
      <nav class="sidebar-menu">
        <router-link
          v-for="m in menus"
          :key="m.path"
          :to="m.path"
          class="side-item"
          :class="{ active: activeMenu === m.path }"
        >
          <span class="side-icon">{{ m.icon }}</span>
          {{ m.label }}
        </router-link>
      </nav>
    </aside>

    <div class="main">
      <header class="topbar">
        <el-tag size="small" effect="plain" type="success">
          {{ '店长' }} · {{ user.nickname }}
        </el-tag>
        <el-button size="small" link type="danger" @click="logout">退出</el-button>
      </header>
      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.admin-layout {
  display: flex;
  min-height: 100vh;
  background: var(--color-bg-base);
}

.sidebar {
  width: 216px;
  flex-shrink: 0;
  background: #0f172a; /* slate-900 深色侧栏，与科技绿撞色 */
  padding: 20px 12px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #fff;
  font-weight: 700;
  font-size: 16px;
  padding: 8px 10px 20px;
}

.brand-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--color-emerald), var(--color-cyan));
}

.sidebar-menu {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.side-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 14px;
  border-radius: 10px;
  color: #cbd5e1;
  text-decoration: none;
  font-size: 14px;
  transition: all 0.2s;
}

.side-item:hover {
  background: rgba(13, 148, 136, 0.15);
  color: #fff;
}

.side-item.active {
  background: var(--color-emerald);
  color: #fff;
  box-shadow: 0 6px 16px rgba(13, 148, 136, 0.35);
}

.side-icon {
  font-size: 15px;
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  height: 56px;
  padding: 0 24px;
  background: var(--color-card);
  border-bottom: 1px solid var(--color-border);
}

.content {
  flex: 1;
  padding: 24px;
  overflow: auto;
}
</style>