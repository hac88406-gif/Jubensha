<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'

const user = useUserStore()
const router = useRouter()

// 知识图谱页需要更宽的画布，突破默认 1200px 限制
const isGraphPage = computed(() =>
  router.currentRoute.value.path.startsWith('/player/knowledge-graph')
)

// 玩家端菜单
const menus = [
  { path: '/player/scripts', label: '剧本大厅' },
  { path: '/player/orders', label: '我的订单' },
  { path: '/agent', label: 'AI 陪练' },
  { path: '/player/knowledge-graph', label: '知识图谱' },
]

/** 当前激活菜单 */
const activeMenu = computed(() => {
  const m = menus.find((x) => router.currentRoute.value.path.startsWith(x.path))
  return m ? m.path : menus[0].path
})

function logout() {
  user.logout()
  router.push('/login')
}
</script>

<template>
  <div class="player-layout">
    <header class="topbar">
      <div class="brand">
        <span class="brand-dot" />
        <span class="brand-name">剧本杀预约</span>
      </div>
      <nav class="menu">
        <template v-for="m in menus" :key="m.path">
          <router-link
            :to="m.path"
            class="menu-item"
            :class="{ active: activeMenu === m.path }"
          >
            {{ m.label }}
          </router-link>
        </template>
      </nav>
      <div class="right">
        <el-tag size="small" effect="plain" type="success">{{ user.nickname }}</el-tag>
        <el-button size="small" link type="danger" @click="logout">退出</el-button>
      </div>
    </header>
    <main class="content" :class="{ 'content--wide': isGraphPage }">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.player-layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--color-bg-base);
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  gap: 32px;
  padding: 0 32px;
  height: 60px;
  background: var(--color-card);
  border-bottom: 1px solid var(--color-border);
  box-shadow: 0 2px 12px rgba(13, 148, 136, 0.06);
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  font-size: 17px;
  color: var(--color-emerald);
  white-space: nowrap;
}

.brand-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--color-emerald), var(--color-cyan));
}

.menu {
  display: flex;
  gap: 6px;
  flex: 1;
}

.menu-item {
  padding: 8px 16px;
  border-radius: 10px;
  color: var(--color-text-sub);
  text-decoration: none;
  font-size: 14px;
  transition: all 0.2s;
}

.menu-item:hover {
  color: var(--color-emerald);
  background: rgba(13, 148, 136, 0.06);
}

.menu-item.active {
  color: #fff;
  background: var(--color-emerald);
  box-shadow: 0 4px 12px rgba(13, 148, 136, 0.3);
}

.right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.content {
  flex: 1;
  padding: 24px 32px;
  max-width: 1200px;
  width: 100%;
  margin: 0 auto;
  box-sizing: border-box;
}

/* 知识图谱页：铺满窗口宽度，给力导向图更大的画布 */
.content--wide {
  max-width: none;
  padding: 20px 24px;
}

@media (max-width: 768px) {
  .topbar {
    padding: 0 16px;
    gap: 12px;
  }
  .menu {
    gap: 0;
  }
  .menu-item {
    padding: 8px 10px;
  }
  .content {
    padding: 16px;
  }
}
</style>