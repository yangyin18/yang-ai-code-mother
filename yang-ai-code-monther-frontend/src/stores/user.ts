/**
 * 用户 Store(登录态)
 *
 * 用 Pinia 统一管理登录状态,并持久化到 localStorage:
 *   刷新页面后自动恢复登录态(token + 用户信息)。
 *
 * 学习点:token 是前后端鉴权的通行证,前端存到 localStorage,
 * 后续在 request.ts 请求拦截器里读到它并注入请求头。
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { login, logout, register } from '@/api/user'
import type { UserInfo } from '@/types'

/** localStorage 存储 key */
const TOKEN_KEY = 'yang-ai-token'
const USER_KEY = 'yang-ai-user'

/** 安全解析 localStorage 里的 JSON */
function loadStoredUser(): UserInfo | null {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) ?? 'null')
  } catch {
    return null
  }
}

export const useUserStore = defineStore('user', () => {
  /** 登录令牌(空字符串 = 未登录) */
  const token = ref(localStorage.getItem(TOKEN_KEY) ?? '')

  /** 当前用户信息 */
  const userInfo = ref<UserInfo | null>(loadStoredUser())

  /** 是否已登录(学习点:computed 根据 token 派生) */
  const isLoggedIn = computed(() => !!token.value)

  /** 用户名首字母(用于顶栏头像) */
  const avatarText = computed(() => userInfo.value?.username.slice(0, 1).toUpperCase() ?? '?')

  /** 登录:成功后保存 token + 用户信息到内存和 localStorage */
  async function loginByPassword(username: string, password: string): Promise<UserInfo> {
    const info = await login({ username, password })
    token.value = `mock-token-${Date.now()}`
    userInfo.value = info
    localStorage.setItem(TOKEN_KEY, token.value)
    localStorage.setItem(USER_KEY, JSON.stringify(info))
    return info
  }

  /** 注册(注册成功后由页面引导自动登录) */
  async function registerUser(username: string, password: string): Promise<UserInfo> {
    return register({ username, password })
  }

  /** 退出登录:清空登录态 */
  async function logoutUser(): Promise<void> {
    await logout()
    token.value = ''
    userInfo.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return {
    token,
    userInfo,
    isLoggedIn,
    avatarText,
    loginByPassword,
    registerUser,
    logoutUser,
  }
})
