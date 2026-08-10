<script setup lang="ts">
/**
 * 首页:大厂 Hero 风格
 *
 * 结构(自上而下):
 *  1. Hero 标题区(渐变强调 + 淡入动画)
 *  2. 生成器(渐变边框输入卡片,聚焦发光)
 *  3. 我的应用(当前用户的已生成应用,占位封面)
 *  3.5 应用广场(全部已部署应用,风格对齐 miaoda 首页)
 *  4. 模板卡片(悬停上浮)
 *  5. 特性区(三张能力卡片)
 */
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { listFeaturedApps, listMyApps } from '@/api/generation'
import { useGenerationStore } from '@/stores/generation'
import { useUserStore } from '@/stores/user'

/** 首页"我的应用"卡片数据结构(后端 AppVO 的字段子集) */
interface MyApp {
  id: string
  appName?: string
  cover?: string
  deployUrl?: string
  createTime?: string
}

const router = useRouter()
const store = useGenerationStore()
const userStore = useUserStore()

/** 需求输入 */
const requirement = ref('')

/** 提交中 */
const submitting = ref(false)

/** 我的应用列表 */
const myApps = ref<MyApp[]>([])
const myAppsLoading = ref(false)

/** 精选应用(应用广场)列表 */
const featuredApps = ref<MyApp[]>([])
const featuredLoading = ref(false)

/** 拉取当前用户生成的应用 */
async function loadMyApps() {
  if (!userStore.isLoggedIn) return
  myAppsLoading.value = true
  try {
    myApps.value = await listMyApps(1, 12)
  } catch {
    myApps.value = []
  } finally {
    myAppsLoading.value = false
  }
}

/** 拉取全部已部署应用(应用广场) */
async function loadFeaturedApps() {
  if (!userStore.isLoggedIn) return
  featuredLoading.value = true
  try {
    featuredApps.value = await listFeaturedApps(1, 12)
  } catch {
    featuredApps.value = []
  } finally {
    featuredLoading.value = false
  }
}

/** 登录态变化(或进入首页)时加载"我的应用"与"应用广场" */
watch(() => userStore.isLoggedIn, (v) => {
  if (v) {
    loadMyApps()
    loadFeaturedApps()
  }
}, { immediate: true })

/** 点击应用卡片 */
function openApp(app: MyApp) {
  if (app.deployUrl) {
    window.open(app.deployUrl, '_blank')
  } else {
    message.info('这个应用还没部署上线,回到生成页再试一次吧')
  }
}

/** 点击「我的应用」卡片:进入应用对话页(自己的应用,可对话/查看网站) */
function goChat(app: MyApp) {
  router.push(`/chat/${app.id}`)
}

/** 时间格式化 */
function fmtTime(t?: string): string {
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 封面占位图底色(按应用名 hash 稳定取色) */
function coverHue(name: string): number {
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360
  return h
}

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
  // 生成/部署走真实后端,需要登录
  if (!userStore.isLoggedIn) {
    message.info('请先登录后再开始生成')
    router.push('/login')
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

    <!-- 3. 我的应用 -->
    <section v-if="userStore.isLoggedIn" class="my-apps">
      <div class="section-title">
        <span class="section-line" />
        <h2>我的应用</h2>
        <span v-if="myApps.length" class="section-count">{{ myApps.length }} 个</span>
      </div>

      <div v-if="myAppsLoading" class="my-apps-state">加载中...</div>
      <div v-else-if="myApps.length === 0" class="my-apps-state">
        还没有生成过应用,在上方输入一句话,生成你的第一个吧 ✨
      </div>
      <div v-else class="app-grid">
        <div
          v-for="app in myApps"
          :key="app.id"
          class="app-card"
          @click="goChat(app)"
        >
          <div class="app-cover">
            <img v-if="app.cover" :src="app.cover" :alt="app.appName || '应用'" />
            <div
              v-else
              class="cover-placeholder"
              :style="{ background: `linear-gradient(135deg, hsl(${coverHue(app.appName || '')} 70% 55%), hsl(${(coverHue(app.appName || '') + 60) % 360} 70% 40%))` }"
            >
              {{ (app.appName || 'AI').slice(0, 1) }}
            </div>
            <span v-if="app.deployUrl" class="cover-badge">已部署</span>
          </div>
          <div class="app-info">
            <div class="app-name">{{ app.appName || '未命名应用' }}</div>
            <div class="app-meta">
              {{ fmtTime(app.createTime) }}
              <span class="app-action">进入对话 ›</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 3.5 应用广场:全部已部署应用(风格对齐 miaoda 首页) -->
    <section v-if="userStore.isLoggedIn" class="my-apps featured">
      <div class="section-title">
        <span class="section-line" />
        <h2>应用广场</h2>
        <span v-if="featuredApps.length" class="section-count">{{ featuredApps.length }} 个</span>
      </div>

      <div v-if="featuredLoading" class="my-apps-state">加载中...</div>
      <div v-else-if="featuredApps.length === 0" class="my-apps-state">
        应用广场由管理员精选维护,敬请期待 ✨
      </div>
      <div v-else class="app-grid">
        <div
          v-for="app in featuredApps"
          :key="app.id"
          class="app-card"
          @click="openApp(app)"
        >
          <div class="app-cover">
            <img v-if="app.cover" :src="app.cover" :alt="app.appName || '应用'" />
            <div
              v-else
              class="cover-placeholder"
              :style="{ background: `linear-gradient(135deg, hsl(${coverHue(app.appName || '')} 70% 55%), hsl(${(coverHue(app.appName || '') + 60) % 360} 70% 40%))` }"
            >
              {{ (app.appName || 'AI').slice(0, 1) }}
            </div>
            <span v-if="app.deployUrl" class="cover-badge">已部署</span>
          </div>
          <div class="app-info">
            <div class="app-name">{{ app.appName || '未命名应用' }}</div>
            <div class="app-meta">{{ fmtTime(app.createTime) }}</div>
          </div>
        </div>
      </div>
    </section>

    <!-- 4. 模板卡片 -->
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

    <!-- 5. 特性区 -->
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
  border: 1px solid rgba(22, 119, 255, 0.35);
  background: rgba(22, 119, 255, 0.08);
  color: #1677ff;
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
    rgba(22, 119, 255, 0.55),
    rgba(168, 85, 247, 0.3) 50%,
    rgba(21, 27, 38, 0.06)
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
  box-shadow: 0 8px 24px rgba(22, 119, 255, 0.25);
  transition: transform 0.2s ease, box-shadow 0.2s ease, opacity 0.2s;
}

.glow-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 12px 34px rgba(22, 119, 255, 0.4);
}

.glow-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/* ---------- 我的应用 ---------- */
.my-apps {
  animation: fade-up 0.6s ease 0.2s both;
}

.section-count {
  font-size: 12px;
  color: var(--text-3);
  margin-left: 2px;
}

.my-apps-state {
  padding: 28px 20px;
  text-align: center;
  color: var(--text-3);
  font-size: 14px;
  background: rgba(21, 27, 38, 0.02);
  border: 1px dashed var(--border);
  border-radius: var(--radius);
}

.app-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
}

.app-card {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.25s ease, border-color 0.25s ease, box-shadow 0.25s ease;
}

.app-card:hover {
  transform: translateY(-4px);
  border-color: rgba(22, 119, 255, 0.5);
  box-shadow: var(--glow);
}

.app-cover {
  position: relative;
  height: 120px;
  overflow: hidden;
}

.app-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.cover-placeholder {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  font-size: 44px;
  font-weight: 800;
  color: rgba(255, 255, 255, 0.92);
  text-shadow: 0 2px 12px rgba(0, 0, 0, 0.25);
}

.cover-badge {
  position: absolute;
  top: 10px;
  right: 10px;
  font-size: 11px;
  padding: 2px 9px;
  border-radius: 999px;
  color: #fff;
  background: rgba(16, 185, 129, 0.9);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}

.app-info {
  padding: 12px 14px;
}

.app-name {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.app-meta {
  font-size: 12px;
  color: var(--text-3);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.app-action {
  color: var(--primary);
  font-size: 12px;
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
  border-color: rgba(22, 119, 255, 0.5);
  box-shadow: var(--glow);
}

.t-icon {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  font-size: 22px;
  background: rgba(22, 119, 255, 0.1);
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
  background: rgba(21, 27, 38, 0.02);
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
