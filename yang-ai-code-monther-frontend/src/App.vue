<script setup lang="ts">
/**
 * 根组件
 *
 * 核心职责:
 *  1. 用 a-config-provider 全局注入 Antd 浅色主题(学习点:antd 主题定制)
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

/** Antd 主题配置:浅色算法(defaultAlgorithm)+ 品牌 token(对齐 miaoda 蓝→紫) */
const themeConfig = {
  algorithm: theme.defaultAlgorithm,
  token: {
    colorPrimary: '#1677ff', // 主色(品牌蓝)
    colorInfo: '#1677ff',
    colorLink: '#1677ff',
    colorBgBase: '#ffffff', // 全局底色
    colorBgContainer: '#ffffff', // 组件容器底色
    colorBgElevated: '#ffffff', // 弹出层(下拉/气泡)底色
    colorBorder: '#e5e9ed',
    colorTextBase: '#151b26',
    borderRadius: 10, // 组件圆角
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
