/**
 * 生成流程 Store(状态管理)
 *
 * 用 Pinia 管理「生成应用」整个流程的共享状态:
 *   requirement  : 用户输入的需求
 *   steps        : 生成步骤列表
 *   currentStep  : 当前进行到第几步(驱动生成页的进度动画)
 *   app          : 生成结果
 *
 * 为什么放进 store 而不放在单个页面里?
 * 因为「输入需求」发生在首页、「生成动画」在生成页、「查看结果」在结果页,
 * 三个页面需要共享同一份状态,用 Pinia 最合适。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { GENERATE_STEPS } from '@/api/mock'
import { generateApp } from '@/api/generation'
import type { GeneratedApp, GenerateStep } from '@/types'

export const useGenerationStore = defineStore('generation', () => {
  /** 用户输入的需求描述 */
  const requirement = ref('')

  /** 生成步骤(与 mock 中一致) */
  const steps = ref<GenerateStep[]>(GENERATE_STEPS)

  /** 当前步骤下标,0 表示还没开始,steps.length 表示全部完成 */
  const currentStep = ref(0)

  /** 生成结果 */
  const app = ref<GeneratedApp | null>(null)

  /** 是否正在生成 */
  const generating = ref(false)

  /** 记录用户输入的需求 */
  function setRequirement(text: string) {
    requirement.value = text
  }

  /**
   * 启动生成流程
   *
   * 流程:逐步推进进度动画 → 调用生成接口 → 保存结果
   * 返回生成的 app,调用方拿到后跳转到结果页。
   */
  async function start(): Promise<GeneratedApp> {
    generating.value = true
    currentStep.value = 0
    app.value = null

    // 第一步一步推进进度动画,模拟 AI「边想边写」的过程
    for (let i = 1; i <= steps.value.length; i++) {
      await sleep(800)
      currentStep.value = i
    }

    // 真正调用「生成」接口
    const result = await generateApp(requirement.value)
    app.value = result
    generating.value = false
    return result
  }

  /** 重置整个流程状态 */
  function reset() {
    requirement.value = ''
    currentStep.value = 0
    app.value = null
    generating.value = false
  }

  return {
    requirement,
    steps,
    currentStep,
    app,
    generating,
    setRequirement,
    start,
    reset,
  }
})

/** 简单 sleep 工具 */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
