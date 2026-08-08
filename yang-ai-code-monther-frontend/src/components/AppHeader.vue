<script setup lang="ts">
/**
 * 顶部导航栏
 *  - 毛玻璃吸顶
 *  - 未登录:右侧「登录 / 注册」按钮
 *  - 已登录:头像 + 用户名 + 退出登录
 */
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

function goHome() {
  router.push('/')
}

function goLogin() {
  router.push('/login')
}

function goRegister() {
  router.push('/register')
}

/** 退出登录 */
async function handleLogout() {
  await userStore.logoutUser()
  message.success('已退出登录')
  router.push('/')
}
</script>

<template>
  <header class="app-header">
    <div class="header-inner">
      <div class="logo" @click="goHome">
        <div class="logo-mark">⚡</div>
        <span class="logo-text">AI 零代码平台</span>
      </div>

      <!-- 已登录:用户信息 -->
      <div v-if="userStore.isLoggedIn" class="user-box">
        <div class="user-avatar">{{ userStore.avatarText }}</div>
        <span class="user-name">{{ userStore.userInfo?.username }}</span>
        <a-button size="small" type="text" @click="handleLogout">退出</a-button>
      </div>

      <!-- 未登录:登录 / 注册按钮 -->
      <div v-else class="auth-buttons">
        <a-button size="small" type="text" @click="goLogin">登录</a-button>
        <a-button size="small" type="primary" @click="goRegister">注册</a-button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: 100;
  background: rgba(11, 15, 26, 0.72);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--border);
}

.header-inner {
  max-width: 1120px;
  margin: 0 auto;
  padding: 12px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  user-select: none;
}

.logo-mark {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  font-size: 17px;
  background: var(--gradient);
  border-radius: 10px;
  box-shadow: 0 0 20px rgba(99, 102, 241, 0.45);
}

.logo-text {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.2px;
}

/* 登录 / 注册按钮 */
.auth-buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 已登录用户区 */
.user-box {
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-avatar {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--gradient);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  box-shadow: 0 0 12px rgba(99, 102, 241, 0.4);
}

.user-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--text);
}
</style>
