<script setup lang="ts">
/**
 * 根组件
 *
 * 核心职责:
 *  1. 用 a-config-provider 全局注入 Antd 深色主题(学习点:antd 主题定制)
 *  2. 整体布局骨架:顶栏 + 路由出口
 */
import { theme } from 'ant-design-vue'
import AppHeader from '@/components/AppHeader.vue'

/** Antd 主题配置:深色算法(darkAlgorithm)+ 品牌 token */
const themeConfig = {
  algorithm: theme.darkAlgorithm,
  token: {
    colorPrimary: '#6366f1', // 主色(品牌紫蓝)
    colorInfo: '#6366f1',
    colorLink: '#818cf8',
    colorBgBase: '#0b0f1a', // 全局底色
    colorBgContainer: '#121826', // 组件容器底色
    colorBgElevated: '#181f33', // 弹出层(下拉/气泡)底色
    colorBorder: 'rgba(255,255,255,0.12)',
    colorTextBase: '#e5e7eb',
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
