/**
 * 生成流程 Store(状态管理)
 *
 * 用 Pinia 管理「生成应用」整个流程的共享状态:
 *   requirement   : 用户输入的需求
 *   steps         : 生成步骤列表
 *   currentStep   : 当前进行到第几步(驱动生成页的进度动画)
 *   streamingCode : AI 流式生成过程中累积的代码(生成页实时展示)
 *   app           : 生成结果(真实后端:创建应用 → SSE 流式生成 → 查详情)
 *
 * 为什么放进 store 而不放在单个页面里?
 * 因为「输入需求」发生在首页、「生成动画」在生成页、「查看结果」在结果页,
 * 三个页面需要共享同一份状态,用 Pinia 最合适。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { createApp, GENERATE_STEPS, generateAppStream, getAppDetail } from '@/api/generation'
import type { GenerateStep, GeneratedResult } from '@/types'

export const useGenerationStore = defineStore('generation', () => {
  /** 用户输入的需求描述 */
  const requirement = ref('')

  /** 生成步骤 */
  const steps = ref<GenerateStep[]>(GENERATE_STEPS)

  /** 当前步骤下标,0 表示还没开始,steps.length 表示全部完成 */
  const currentStep = ref(0)

  /** SSE 流式生成过程中累积的原始代码 */
  const streamingCode = ref('')

  /** 生成结果 */
  const app = ref<GeneratedResult | null>(null)

  /** 是否正在生成 */
  const generating = ref(false)

  /** SSE 连接是否已建立(后端 started 事件已到) */
  const streamConnected = ref(false)

  /** 流开始建立的时间戳(用于展示已等待时长) */
  const streamStartedAt = ref<number | null>(null)

  /** 已接收原始字节数(含 JSON 包裹,可看到首 token 前也在流动) */
  const rawReceived = ref(0)

  /** 记录用户输入的需求 */
  function setRequirement(text: string) {
    requirement.value = text
  }

  /**
   * 启动生成流程
   *
   * 流程:推进进度动画 → 创建应用 → SSE 流式生成(边生成边累积代码,供实时展示)
   *      → 查详情(名称/部署地址) → 保存结果
   * 「AI 生成代码」这一步在流式期间保持进行中,全部完成才跳到结果页。
   */
  async function start(signal?: AbortSignal): Promise<GeneratedResult> {
    generating.value = true
    currentStep.value = 0
    app.value = null
    streamingCode.value = ''
    streamConnected.value = false
    streamStartedAt.value = null
    rawReceived.value = 0

    // 前几步快速推进动画;「AI 生成代码」在真实流式生成期间保持进行中
    for (let i = 1; i < steps.value.length; i++) {
      await sleep(600)
      currentStep.value = i
    }

    // 真实后端闭环:创建应用 → SSE 流式生成 → 查详情
    const appName = requirement.value.trim().slice(0, 20) || '未命名应用'
    const appId = await createApp(appName, requirement.value)
    const payload = await generateAppStream(
      appId,
      requirement.value,
      (chunk) => {
        // 后端注释:每个 chunk 是增量文本,不能整段 trim,直接 append
        streamingCode.value += chunk
        rawReceived.value += chunk.length
      },
      {
        signal,
        onStarted: () => {
          streamConnected.value = true
          streamStartedAt.value = streamStartedAt.value ?? Date.now()
        },
        onHeartbeat: () => {
          streamStartedAt.value = streamStartedAt.value ?? Date.now()
        },
      },
    )
    const detail = await getAppDetail(appId)
    app.value = {
      ...payload,
      appId,
      name: detail.appName || appName,
      deployUrl: detail.deployUrl,
    }
    currentStep.value = steps.value.length
    generating.value = false
    return app.value
  }

  /** 重置整个流程状态 */
  function reset() {
    requirement.value = ''
    currentStep.value = 0
    app.value = null
    streamingCode.value = ''
    generating.value = false
    streamConnected.value = false
    streamStartedAt.value = null
    rawReceived.value = 0
  }

  return {
    requirement,
    steps,
    currentStep,
    streamingCode,
    app,
    generating,
    streamConnected,
    streamStartedAt,
    rawReceived,
    setRequirement,
    start,
    reset,
  }
})

/** 简单 sleep 工具 */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
