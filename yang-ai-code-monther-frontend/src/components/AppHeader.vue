<script setup lang="ts">
/**
 * 顶部导航栏(终端状态栏)
 *  - 毛玻璃吸顶,深色 + 底部绿线
 *  - Logo `>_ AI.CODE_TERMINAL`(等宽 + 闪烁光标)
 *  - 导航 `~/首页 ~/我的对话 ~/应用管理 ~/对话管理`(admin),等宽,active 绿
 *  - 右侧 `● ONLINE` 状态点 + 用户名 + 退出
 *  - 未登录:[ 登录 ] / [ 注册 ] 终端按钮
 */
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

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
      <div class="logo mono" @click="router.push('/')">
        <span class="logo-prompt">>_</span>
        <span class="logo-text gradient-text">AI.CODE_TERMINAL</span>
      </div>

      <!-- 已登录:导航 + 状态 + 用户信息 -->
      <div v-if="userStore.isLoggedIn" class="user-box">
        <nav class="nav-links">
          <router-link to="/" class="nav-link home-link">~/首页</router-link>
          <router-link to="/conversations" class="nav-link">~/我的对话</router-link>
          <router-link v-if="userStore.userInfo?.userRole === 'admin'" to="/admin/apps" class="nav-link">~/应用管理</router-link>
          <router-link v-if="userStore.userInfo?.userRole === 'admin'" to="/admin/chat" class="nav-link">~/对话管理</router-link>
        </nav>
        <span class="status-online mono"><span class="live-dot" />ONLINE</span>
        <span class="user-name mono">{{ userStore.userInfo?.username }}</span>
        <button type="button" class="ghost-btn mono" @click="handleLogout">[ 退出 ]</button>
      </div>

      <!-- 未登录:登录 / 注册 终端按钮 -->
      <div v-else class="auth-buttons">
        <button type="button" class="ghost-btn mono" @click="goLogin">[ 登录 ]</button>
        <button type="button" class="reg-btn mono" @click="goRegister">[ 注册 ]</button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: 100;
  background: rgba(7, 11, 16, 0.82);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-bottom: 1px solid rgba(0, 255, 157, 0.14);
  box-shadow: 0 1px 0 rgba(34, 211, 238, 0.04), 0 3px 14px rgba(0, 0, 0, 0.3);
}

.header-inner {
  max-width: 1120px;
  margin: 0 auto;
  padding: 11px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

/* ---------- Logo ---------- */
.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;
}

.logo-prompt {
  color: var(--primary);
  font-weight: 700;
  font-size: 16px;
  text-shadow: 0 0 4px rgba(0, 255, 157, 0.35);
}

.logo-text {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.4px;
}

/* ---------- 导航 ---------- */
.nav-links {
  display: flex;
  align-items: center;
  gap: 2px;
}

.nav-link {
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 13px;
  color: var(--text-2);
  padding: 5px 11px;
  border-radius: 6px;
  text-decoration: none;
  transition: all 0.18s;
  border-bottom: 2px solid transparent;
}

.nav-link:hover {
  color: var(--text);
  background: rgba(0, 255, 157, 0.06);
}

.nav-link:not(.home-link).router-link-active,
.nav-link.home-link.router-link-exact-active {
  color: var(--primary);
  border-bottom-color: var(--primary);
  text-shadow: 0 0 6px rgba(0, 255, 157, 0.25);
  background: rgba(0, 255, 157, 0.05);
}

/* ---------- 用户区 ---------- */
.user-box {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status-online {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--success);
  letter-spacing: 0.6px;
}

.user-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}

.ghost-btn {
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--border-strong);
  border-radius: 6px;
  background: transparent;
  color: var(--text-2);
  font-size: 12px;
  cursor: pointer;
  letter-spacing: 0.4px;
  transition: all 0.18s;
}

.ghost-btn:hover {
  color: var(--primary);
  border-color: rgba(0, 255, 157, 0.5);
  box-shadow: 0 0 6px rgba(0, 255, 157, 0.08);
}

/* ---------- 未登录按钮 ---------- */
.auth-buttons {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 注册按钮(小号绿渐变填充,终端感;不用 .glow-btn 避免与首页/登录大 CTA 选择器冲突) */
.reg-btn {
  height: 30px;
  padding: 0 16px;
  border: 1px solid rgba(0, 255, 157, 0.6);
  border-radius: 6px;
  background: var(--gradient);
  color: #04120c;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  letter-spacing: 0.4px;
  box-shadow: 0 0 6px rgba(0, 255, 157, 0.12);
  transition: all 0.18s;
}

.reg-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 0 10px rgba(0, 255, 157, 0.18);
}

@media (max-width: 760px) {
  .status-online,
  .user-name {
    display: none;
  }
}
</style>
