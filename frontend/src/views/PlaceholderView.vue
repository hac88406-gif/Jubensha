<script setup>
import { useRoute } from 'vue-router'

// S1 通用占位页：S2~S5 各业务页面前，先保证路由可跳转、导航可点击
const route = useRoute()
</script>

<template>
  <div class="placeholder u-card">
    <el-empty :description="route.meta.placeholder || '页面建设中'">
      <template #image>
        <!-- 纯 CSS 占位图形，不引图片资源 -->
        <div class="ph-ring"><span>🚧</span></div>
      </template>
    </el-empty>
  </div>
</template>

<style scoped>
.placeholder {
  padding: 48px 24px;
  min-height: 60vh;
  display: flex;
  align-items: center;
  justify-content: center;
}

.ph-ring {
  width: 72px;
  height: 72px;
  margin: 0 auto;
  border-radius: 50%;
  border: 2px dashed var(--color-emerald);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
}
</style>