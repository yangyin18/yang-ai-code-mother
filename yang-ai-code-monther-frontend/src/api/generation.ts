/**
 * 生成相关接口
 *
 * 目前统一走 mock 数据(见 mock.ts)。
 * 将来后端接口就绪后,把 generateApp 的实现替换成真实请求即可,
 * 调用方(store / 页面)完全不用改。
 */
import { mockGenerate } from './mock'
import type { GeneratedApp } from '@/types'

/**
 * 根据需求描述生成应用
 *
 * @param requirement 用户输入的需求描述
 */
export async function generateApp(requirement: string): Promise<GeneratedApp> {
  // 真实后端接入示例(替换掉下面这一行):
  // return request<GeneratedApp>({
  //   url: '/app/generate',
  //   method: 'POST',
  //   data: { requirement },
  // })
  return mockGenerate(requirement)
}
