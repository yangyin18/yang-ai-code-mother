/**
 * axios 请求封装
 *
 * 统一管理:
 *  1. baseURL(与后端 context-path /api 对应)
 *  2. 请求拦截器(可注入 token、日志等)
 *  3. 响应拦截器(统一解包后端 BaseResponse,统一错误提示)
 */
import axios, {
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { message } from 'ant-design-vue'

/**
 * 后端统一响应结构(对应后端 BaseResponse<T>)
 */
export interface ApiResponse<T = unknown> {
  code: number
  data: T
  message: string
}

/** 创建 axios 实例 */
const service = axios.create({
  // 后端 context-path 为 /api,开发环境由 vite 代理转发到 8123 端口
  baseURL: '/api',
  timeout: 30000,
})

/** 请求拦截器:发送请求之前统一处理 */
service.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  // 示例:从 localStorage 取出 token 注入请求头
  // const token = localStorage.getItem('token')
  // if (token) {
  //   config.headers.Authorization = `Bearer ${token}`
  // }
  return config
})

/** 响应拦截器:拿到后端响应后统一解包 */
service.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const res = response.data
    // 业务成功 code = 0,直接把业务数据返回出去
    if (res.code === 0) {
      return res.data as unknown as AxiosResponse
    }
    // 业务失败:统一弹提示并 reject
    message.error(res.message || '请求失败')
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  (error) => {
    // 网络错误 / 超时等
    message.error(error?.message || '网络异常,请稍后重试')
    return Promise.reject(error)
  },
)

/**
 * 统一请求方法
 *
 * 由于响应拦截器已经解包了 BaseResponse 的 data,
 * 这里把返回类型断言为业务数据类型 T,调用方直接拿到数据。
 *
 * @example
 * const app = await request<GeneratedApp>({ url: '/app/generate', method: 'POST', data: { requirement } })
 */
export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return service.request(config) as Promise<T>
}
