/**
 * 用户相关接口
 *
 * 目前统一走 mock(见 mock.ts)。后端就绪后,把实现替换成真实请求即可,
 * 调用方(store / 页面)无需改动。
 */
import { mockLogin, mockRegister } from './mock'
import type { UserInfo } from '@/types'

/** 登录/注册参数 */
export interface AuthParams {
  username: string
  password: string
}

/**
 * 登录
 */
export async function login(params: AuthParams): Promise<UserInfo> {
  // 真实后端接入示例:
  // return request<UserInfo>({ url: '/user/login', method: 'POST', data: params })
  return mockLogin(params.username, params.password)
}

/**
 * 注册
 */
export async function register(params: AuthParams): Promise<UserInfo> {
  // return request<UserInfo>({ url: '/user/register', method: 'POST', data: params })
  return mockRegister(params.username, params.password)
}

/**
 * 退出登录(真实后端:通知服务器使 token 失效)
 */
export async function logout(): Promise<void> {
  // return request<void>({ url: '/user/logout', method: 'POST' })
  return Promise.resolve()
}
