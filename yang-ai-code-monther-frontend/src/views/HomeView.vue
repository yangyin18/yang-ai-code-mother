<script setup lang="ts">
/**
 * 首页:大厂 Hero 风格
 *
 * 结构(自上而下):
 *  1. Hero 标题区(渐变强调 + 淡入动画)
 *  2. 生成器(渐变边框输入卡片,聚焦发光)
 *  3. 模板卡片(悬停上浮)
 *  4. 特性区(三张能力卡片)
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useGenerationStore } from '@/stores/generation'

const router = useRouter()
const store = useGenerationStore()

/** 需求输入 */
const requirement = ref('')

/** 提交中 */
const submitting = ref(false)

/** 模板卡片数据 */
const templates = [
  { icon: '✅', label: '打卡助手', desc: '每日签到 · 连续坚持天数', example: '帮我做一个每日打卡签到的应用,能记录连续打卡天数' },
  { icon: '💰', label: '记账本', desc: '收支明细 · 一目了然', example: '做一个记账本应用,记录每天的收支情况' },
  { icon: '📋', label: '待办清单', desc: '任务管理 · 随时勾选完成', example: '帮我做一个个人的待办清单,可以添加和勾选完成' },
]

/** 点击模板:填入示例需求 */
function useTemplate(example: string) {
  requirement.value = example
}

/** 点击「立即生成」 */
async function handleGenerate() {
  const text = requirement.value.trim()
  if (!text) {
    message.warning('先描述一下你想生成什么样的应用吧')
    return
  }
  submitting.value = true
  try {
    store.setRequirement(text)
    await router.push('/generate')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="home">
    <!-- 1. Hero 标题区 -->
    <section class="hero">
      <div class="badge">✨ AI-Powered App Builder</div>
      <h1 class="title">
        一句话,生成你的
        <span class="gradient-text">专属应用</span>
      </h1>
      <p class="subtitle">描述需求,AI 自动完成设计、代码与交互 —— 零门槛,几分钟上线</p>
    </section>

    <!-- 2. 生成器 -->
    <section class="generator">
      <div class="input-shell">
        <div class="input-panel">
          <a-textarea
            v-model:value="requirement"
            class="requirement-input"
            :rows="3"
            placeholder="例如:帮我做一个每日打卡签到的应用,记录连续坚持天数"
            :maxlength="200"
            show-count
            @press-enter="handleGenerate"
          />
          <div class="input-footer">
            <span class="hint">试试:打卡 / 记账 / 待办清单</span>
            <button class="glow-btn" :disabled="submitting" @click="handleGenerate">
              {{ submitting ? '生成中...' : '✨ 立即生成' }}
            </button>
          </div>
        </div>
      </div>
    </section>

    <!-- 3. 模板卡片 -->
    <section class="templates">
      <div class="section-title">
        <span class="section-line" />
        <h2>选择模板快速开始</h2>
      </div>
      <div class="template-grid">
        <div
          v-for="t in templates"
          :key="t.label"
          class="template-card"
          @click="useTemplate(t.example)"
        >
          <div class="t-icon">{{ t.icon }}</div>
          <div class="t-name">{{ t.label }}</div>
          <div class="t-desc">{{ t.desc }}</div>
        </div>
      </div>
    </section>

    <!-- 4. 特性区 -->
    <section class="features">
      <div class="feature-card">
        <div class="f-icon">🧠</div>
        <div class="f-title">智能理解</div>
        <div class="f-desc">AI 解析你的自然语言需求,自动拆解功能</div>
      </div>
      <div class="feature-card">
        <div class="f-icon">⚡</div>
        <div class="f-title">秒级生成</div>
        <div class="f-desc">从一句话到可运行应用,只需几秒</div>
      </div>
      <div class="feature-card">
        <div class="f-icon">📦</div>
        <div class="f-title">代码可带走</div>
        <div class="f-desc">完整源码一键下载,想怎么改怎么改</div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 56px;
}

/* ---------- Hero ---------- */
.hero {
  text-align: center;
  padding-top: 28px;
  animation: fade-up 0.6s ease both;
}

.badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 14px;
  border: 1px solid rgba(99, 102, 241, 0.35);
  background: rgba(99, 102, 241, 0.1);
  color: #a5b4fc;
  font-size: 12px;
  font-weight: 500;
  border-radius: 999px;
  margin-bottom: 20px;
}

.title {
  margin: 0 0 14px;
  font-size: 46px;
  font-weight: 800;
  line-height: 1.2;
  letter-spacing: -0.5px;
}

.subtitle {
  margin: 0 auto;
  max-width: 460px;
  color: var(--text-2);
  font-size: 16px;
  line-height: 1.7;
}

/* ---------- 生成器 ---------- */
.generator {
  animation: fade-up 0.6s ease 0.1s both;
}

/* 渐变边框外层:1px 渐变 + 聚焦发光 */
.input-shell {
  padding: 1px;
  border-radius: 20px;
  background: linear-gradient(
    135deg,
    rgba(99, 102, 241, 0.6),
    rgba(139, 92, 246, 0.35) 50%,
    rgba(255, 255, 255, 0.12)
  );
  transition: box-shadow 0.3s ease;
}

.input-shell:focus-within {
  box-shadow: var(--glow);
}

.input-panel {
  background: var(--panel);
  border-radius: 19px;
  padding: 20px 22px 16px;
}

.requirement-input {
  font-size: 16px;
  line-height: 1.7;
  background: transparent !important;
}

.input-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
}

.hint {
  color: var(--text-3);
  font-size: 13px;
}

/* 流光渐变按钮 */
.glow-btn {
  height: 44px;
  padding: 0 28px;
  border: none;
  border-radius: 12px;
  background: var(--gradient);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(99, 102, 241, 0.35);
  transition: transform 0.2s ease, box-shadow 0.2s ease, opacity 0.2s;
}

.glow-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 12px 34px rgba(99, 102, 241, 0.5);
}

.glow-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/* ---------- 模板 ---------- */
.templates {
  animation: fade-up 0.6s ease 0.2s both;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.section-line {
  width: 4px;
  height: 18px;
  border-radius: 2px;
  background: var(--gradient);
}

.section-title h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.template-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.template-card {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 22px 20px;
  cursor: pointer;
  transition: transform 0.25s ease, border-color 0.25s ease, box-shadow 0.25s ease;
}

.template-card:hover {
  transform: translateY(-4px);
  border-color: rgba(99, 102, 241, 0.5);
  box-shadow: var(--glow);
}

.t-icon {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  font-size: 22px;
  background: rgba(99, 102, 241, 0.12);
  border-radius: 12px;
  margin-bottom: 14px;
}

.t-name {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 6px;
}

.t-desc {
  color: var(--text-2);
  font-size: 13px;
}

/* ---------- 特性 ---------- */
.features {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  animation: fade-up 0.6s ease 0.3s both;
}

.feature-card {
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 22px 20px;
}

.f-icon {
  font-size: 22px;
  margin-bottom: 10px;
}

.f-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 6px;
}

.f-desc {
  color: var(--text-2);
  font-size: 13px;
  line-height: 1.6;
}

/* ---------- 动画 ---------- */
@keyframes fade-up {
  from {
    opacity: 0;
    transform: translateY(16px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
