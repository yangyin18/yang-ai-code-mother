<script setup lang="ts">
/**
 * 根组件
 *
 * 核心职责:
 *  1. 用 a-config-provider 全局注入 Antd 深色主题(黑客/终端绿黑风)
 *  2. 整体布局骨架:顶栏 + 路由出口
 */
import { onMounted } from 'vue'
import { theme } from 'ant-design-vue'
import AppHeader from '@/components/AppHeader.vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

/** 启动时恢复登录态(session + Cookie):有本地登录标记才向后端探测 */
onMounted(() => {
  userStore.fetchLoginUser()
})

/** Antd 主题配置:深色算法(darkAlgorithm)+ 终端绿 token(对齐绿黑矩阵) */
const themeConfig = {
  algorithm: theme.darkAlgorithm,
  token: {
    colorPrimary: '#00ff9d', // 主色(终端绿)
    colorInfo: '#00ff9d',
    colorLink: '#22d3ee',
    colorBgBase: '#0a0e14', // 全局底色
    colorBgContainer: '#0d141b', // 组件容器底色
    colorBgElevated: '#10151c', // 弹出层(下拉/气泡)底色
    colorBorder: '#1c2a38',
    colorTextBase: '#d7e3ea',
    borderRadius: 8, // 组件圆角(偏锐利,终端感)
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'PingFang SC', 'Microsoft YaHei', sans-serif",
  },
}
</script>

<template>
  <a-config-provider :theme="themeConfig">
    <div class="app-layout">
      <AppHeader />
      <main class="app-main">
        <router-view />
      </main>
    </div>
  </a-config-provider>
</template>

<style scoped>
.app-layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.app-main {
  flex: 1;
  width: 100%;
  max-width: 1120px;
  margin: 0 auto;
  padding: 48px 24px 80px;
  box-sizing: border-box;
}
</style>
