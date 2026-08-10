/**
 * 用户相关接口(真实后端)
 *
 * 鉴权走 session + Cookie(后端把登录态写进 session,axios 已配置
 * withCredentials,代理转发时自动携带)。调用方(store / 页面)无需改动。
 */
import { request } from './request'
import type { UserInfo } from '@/types'

/** 登录/注册参数 */
export interface AuthParams {
  username: string
  password: string
}

/** 后端登录用户信息(脱敏,无密码) */
interface LoginUserVO {
  id: number
  userAccount: string
  userName?: string
  userAvatar?: string
  userProfile?: string
  userRole?: string
}

/** LoginUserVO 转前端 UserInfo */
function toUserInfo(vo: LoginUserVO): UserInfo {
  return {
    id: String(vo.id),
    username: vo.userAccount || vo.userName || '',
    avatar: vo.userAvatar,
    userRole: vo.userRole,
  }
}

/**
 * 登录
 */
export async function login(params: AuthParams): Promise<UserInfo> {
  const vo = await request<LoginUserVO>({
    url: '/user/login',
    method: 'POST',
    data: { userAccount: params.username, userPassword: params.password },
  })
  return toUserInfo(vo)
}

/**
 * 注册
 */
export async function register(params: AuthParams): Promise<UserInfo> {
  const userId = await request<number>({
    url: '/user/register',
    method: 'POST',
    data: {
      userAccount: params.username,
      userPassword: params.password,
      checkPassword: params.password,
    },
  })
  return { id: String(userId), username: params.username }
}

/**
 * 获取当前登录用户(静默:未登录时返回 null,不弹错误提示)
 */
export async function getLoginUser(): Promise<UserInfo | null> {
  try {
    const vo = await request<LoginUserVO>({
      url: '/user/get/login',
      method: 'GET',
      silent: true,
    })
    return toUserInfo(vo)
  } catch {
    return null
  }
}

/**
 * 退出登录(通知服务器使 session 失效)
 */
export async function logout(): Promise<void> {
  await request<void>({ url: '/user/logout', method: 'POST' })
}
