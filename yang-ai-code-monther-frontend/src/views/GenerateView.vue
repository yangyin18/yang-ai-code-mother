<script setup lang="ts">
/**
 * 生成页:展示 AI「逐步生成」的炫酷过程
 *
 *  - 需求回顾卡片 + 流光进度条
 *  - 步骤节点发光、进行中节点脉冲动画
 *
 * 进入页面自动启动生成(store.start()),全部完成自动跳转结果页。
 * 直接刷新本页(store 无需求)会被引导回首页。
 */
import { computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useGenerationStore } from '@/stores/generation'

const router = useRouter()
const store = useGenerationStore()

/** 当前步骤下标 */
const current = computed(() => store.currentStep)

/** 是否全部完成 */
const allDone = computed(() => store.currentStep >= store.steps.length)

/** 进度百分比 */
const percent = computed(() =>
  Math.round((store.currentStep / store.steps.length) * 100),
)

/** 判断某一步的状态 */
function stepStatus(index: number): 'done' | 'active' | 'pending' {
  if (index < current.value) return 'done'
  if (index === current.value) return 'active'
  return 'pending'
}

onMounted(() => {
  if (!store.requirement.trim()) {
    router.replace('/')
    return
  }
  if (store.currentStep === 0) {
    store.start()
  }
})

watch(current, async (val) => {
  if (val >= store.steps.length) {
    await router.replace('/result')
  }
})
</script>

<template>
  <div class="generate">
    <!-- 需求回顾 + 流光进度 -->
    <div class="req-card">
      <div class="req-head">
        <div class="req-label">
          <span class="pulse-dot" />
          正在为以下需求生成应用
        </div>
        <span class="req-percent">{{ percent }}%</span>
      </div>
      <div class="req-text">「{{ store.requirement }}」</div>

      <!-- 流光进度条 -->
      <div class="bar-track">
        <div class="bar-fill" :style="{ width: percent + '%' }" />
      </div>

      <div class="req-status">
        {{ allDone ? '✨ 生成完成,正在进入预览' : '⏳ AI 正在工作中,请稍候...' }}
      </div>
    </div>

    <!-- 步骤列表 -->
    <div class="steps">
      <div
        v-for="(step, index) in store.steps"
        :key="step.key"
        class="step"
        :class="'s-' + stepStatus(index)"
      >
        <!-- 左侧:节点 + 连接线 -->
        <div class="step-left">
          <div class="step-node">
            <span v-if="stepStatus(index) === 'done'" class="node-check">✓</span>
            <a-spin v-else-if="stepStatus(index) === 'active'" size="small" />
            <span v-else class="node-dot" />
          </div>
          <div v-if="index < store.steps.length - 1" class="step-line" />
        </div>

        <!-- 右侧:文字 -->
        <div class="step-body" :class="{ 'is-active': stepStatus(index) === 'active' }">
          <div class="step-title">
            {{ step.title }}
            <span v-if="stepStatus(index) === 'active'" class="active-tag">进行中</span>
            <span v-else-if="stepStatus(index) === 'done'" class="done-tag">完成</span>
          </div>
          <div class="step-desc">{{ step.desc }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.generate {
  max-width: 680px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 28px;
  animation: fade-up 0.5s ease both;
}

/* ---------- 需求回顾卡片 ---------- */
.req-card {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 22px 24px;
  box-shadow: var(--shadow);
}

.req-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.req-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-2);
}

.pulse-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--primary);
  box-shadow: 0 0 0 0 rgba(99, 102, 241, 0.6);
  animation: pulse 1.6s infinite;
}

.req-percent {
  font-size: 15px;
  font-weight: 700;
  background: var(--gradient);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.req-text {
  margin: 14px 0 18px;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.6;
}

/* 流光进度条 */
.bar-track {
  height: 6px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.06);
  overflow: hidden;
}

.bar-fill {
  position: relative;
  height: 100%;
  border-radius: 6px;
  background: var(--gradient);
  transition: width 0.6s ease;
}

/* 扫描光斑 */
.bar-fill::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  width: 48px;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.5), transparent);
  animation: sweep 1.2s linear infinite;
}

.req-status {
  margin-top: 12px;
  font-size: 13px;
  color: var(--text-3);
}

/* ---------- 步骤列表 ---------- */
.steps {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 12px 24px;
  box-shadow: var(--shadow);
}

.step {
  display: flex;
  gap: 16px;
}

/* 左侧节点列 */
.step-left {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 30px;
  flex-shrink: 0;
}

.step-node {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  border: 2px solid var(--border-strong);
  margin-top: 20px;
  flex-shrink: 0;
}

/* 节点状态 */
.s-active .step-node {
  border-color: var(--primary);
  box-shadow: 0 0 0 4px rgba(99, 102, 241, 0.15);
  animation: pulse-node 1.8s infinite;
}

.s-done .step-node {
  border-color: var(--success);
  background: rgba(52, 211, 153, 0.12);
}

.node-check {
  color: var(--success);
  font-weight: 700;
}

.node-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.2);
}

/* 连接线:渐变 */
.step-line {
  width: 2px;
  flex: 1;
  min-height: 30px;
  background: linear-gradient(180deg, var(--border-strong), var(--border));
}

.s-active .step-line {
  background: linear-gradient(180deg, var(--primary), var(--border));
}

.s-done .step-line {
  background: linear-gradient(180deg, var(--success), var(--border));
}

/* 右侧文字 */
.step-body {
  padding: 20px 0 22px;
  flex: 1;
  transition: transform 0.3s ease;
}

.step-body.is-active {
  transform: translateX(4px);
}

.step-title {
  font-size: 16px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}

.active-tag {
  font-size: 11px;
  color: #a5b4fc;
  border: 1px solid rgba(99, 102, 241, 0.5);
  background: rgba(99, 102, 241, 0.12);
  border-radius: 4px;
  padding: 1px 6px;
}

.done-tag {
  font-size: 11px;
  color: var(--success);
  border: 1px solid rgba(52, 211, 153, 0.4);
  background: rgba(52, 211, 153, 0.1);
  border-radius: 4px;
  padding: 1px 6px;
}

.step-desc {
  margin-top: 5px;
  color: var(--text-2);
  font-size: 13px;
}

/* ---------- 动画 ---------- */
@keyframes pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(99, 102, 241, 0.6);
  }
  70% {
    box-shadow: 0 0 0 8px rgba(99, 102, 241, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(99, 102, 241, 0);
  }
}

@keyframes pulse-node {
  0%,
  100% {
    box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.15);
  }
  50% {
    box-shadow: 0 0 0 8px rgba(99, 102, 241, 0);
  }
}

@keyframes sweep {
  from {
    left: -48px;
  }
  to {
    left: 100%;
  }
}

@keyframes fade-up {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
