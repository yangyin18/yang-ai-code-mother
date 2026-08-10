/**
 * 用户 Store(登录态)
 *
 * 用 Pinia 统一管理登录状态,并持久化到 localStorage:
 *   刷新页面后调用后端 GET /user/get/login 恢复登录态(session + Cookie)。
 *
 * 说明:后端鉴权走 session,Cookie 由 axios(withCredentials)自动携带,
 * 前端不再有 token 概念;这里保留一个本地标记,用于决定是否向后端探测恢复。
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getLoginUser, login, logout, register } from '@/api/user'
import type { UserInfo } from '@/types'

/** localStorage 存储 key(带版本号:避免与早期 mock 的残留登录态混淆) */
const LOGIN_KEY = 'yang-ai-logged-in-v2'
const USER_KEY = 'yang-ai-user-v2'

/** 安全解析 localStorage 里的 JSON */
function loadStoredUser(): UserInfo | null {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) ?? 'null')
  } catch {
    return null
  }
}

export const useUserStore = defineStore('user', () => {
  /** 本地登录标记(空字符串 = 未登录,仅用于决定是否向后端探测) */
  const token = ref(localStorage.getItem(LOGIN_KEY) ?? '')

  /** 当前用户信息 */
  const userInfo = ref<UserInfo | null>(loadStoredUser())

  /** 是否已登录(根据用户信息派生) */
  const isLoggedIn = computed(() => !!userInfo.value)

  /** 用户名首字母(用于顶栏头像) */
  const avatarText = computed(() => userInfo.value?.username.slice(0, 1).toUpperCase() ?? '?')

  /** 登录:成功后保存用户信息到内存和 localStorage */
  async function loginByPassword(username: string, password: string): Promise<UserInfo> {
    const info = await login({ username, password })
    userInfo.value = info
    token.value = '1'
    localStorage.setItem(LOGIN_KEY, token.value)
    localStorage.setItem(USER_KEY, JSON.stringify(info))
    return info
  }

  /** 注册(注册成功后由页面引导自动登录) */
  async function registerUser(username: string, password: string): Promise<UserInfo> {
    return register({ username, password })
  }

  /**
   * 应用启动时恢复登录态:有本地标记才向后端探测,
   * 会话有效则刷新用户信息,失效则清空本地状态。
   */
  async function fetchLoginUser(): Promise<UserInfo | null> {
    if (!token.value) {
      return null
    }
    const info = await getLoginUser()
    if (info) {
      userInfo.value = info
      localStorage.setItem(USER_KEY, JSON.stringify(info))
    } else {
      token.value = ''
      userInfo.value = null
      localStorage.removeItem(LOGIN_KEY)
      localStorage.removeItem(USER_KEY)
    }
    return info
  }

  /** 退出登录:清空登录态 */
  async function logoutUser(): Promise<void> {
    try {
      await logout()
    } catch {
      // 忽略退出接口异常,本地照常清空
    }
    token.value = ''
    userInfo.value = null
    localStorage.removeItem(LOGIN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return {
    token,
    userInfo,
    isLoggedIn,
    avatarText,
    loginByPassword,
    registerUser,
    fetchLoginUser,
    logoutUser,
  }
})
