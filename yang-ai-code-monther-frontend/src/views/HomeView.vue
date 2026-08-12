<script setup lang="ts">
/**
 * 首页:黑客终端风(绿黑矩阵)
 *
 * 结构(自上而下):
 *  1. Hero 标题区(等宽 + 闪烁光标 + 渐变强调)
 *  2. 生成器(终端窗口:三圆点标题栏 + `$` 提示符输入框 + [ EXECUTE ] 按钮)
 *  3. 我的应用(当前用户的已生成应用,卡片 = 终端窗口)
 *  3.5 应用广场(全部已部署应用)
 *  4. 模板卡片(悬停上浮)
 *  5. 特性区(三张能力卡片)
 */
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { createApp, deleteApp, downloadAppZip, getAppCode, listFeaturedApps, listMyApps } from '@/api/generation'
import { useUserStore } from '@/stores/user'
import { highlight } from '@/utils/highlight'
import ScrambleText from '@/components/ScrambleText.vue'
import LoopScramble from '@/components/LoopScramble.vue'
import AppCover from '@/components/AppCover.vue'

/** 首页"我的应用"卡片数据结构(后端 AppVO 的字段子集) */
interface MyApp {
  id: string
  appName?: string
  cover?: string
  deployUrl?: string
  userId?: string
  createTime?: string
  /** 最近活跃时间(对话 / 部署 / 改文字都会刷新),卡片时间展示用这个 */
  updateTime?: string
  /** 生成类型:html=快速开发(原生 HTML),vue=深度开发(Vue 项目),决定卡片跳转地址 */
  codeGenType?: string
}

/** 生成模式:html=快速开发(原生 HTML,原逻辑),vue=深度开发(Vue 项目) */
type GenMode = 'html' | 'vue'

const router = useRouter()
const userStore = useUserStore()

/** 需求输入 */
const requirement = ref('')

/** 生成模式(快速开发 / 深度开发),默认快速 */
const genMode = ref<GenMode>('html')

/** 提交中 */
const submitting = ref(false)

/** 我的应用列表 */
const myApps = ref<MyApp[]>([])
/** 我的应用真实总数(后端分页 totalRow;标题上「我的应用 (N)」用总数而非本页条数) */
const myAppsTotal = ref(0)
const myAppsLoading = ref(false)

/** 精选应用(应用广场)列表 */
const featuredApps = ref<MyApp[]>([])
const featuredLoading = ref(false)

/** 拉取当前用户生成的应用(含真实总数) */
async function loadMyApps() {
  if (!userStore.isLoggedIn) return
  myAppsLoading.value = true
  try {
    const res = await listMyApps(1, 12)
    myApps.value = res.records
    myAppsTotal.value = res.total
  } catch {
    myApps.value = []
    myAppsTotal.value = 0
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

/** 点击「我的应用」卡片:统一进对话页(深度开发 Vue 项目也在对话里实时生成) */
function goChat(app: MyApp) {
  router.push(`/chat/${app.id}`)
}

/** 下载 Vue 项目的 ZIP 包(仅深度开发应用,卡片上有「下载」按钮) */
async function handleDownload(app: MyApp) {
  try {
    await downloadAppZip(app.id)
  } catch (e) {
    message.error((e as Error).message || '下载失败')
  }
}

/**
 * 删除后静默刷新「我的应用」:本地先移除(即时反馈),再从后端拉最新 12 个把删掉的槽位补上,
 * 保证列表始终是满的(展示数量不因删除减少,≥10 个)。不置 loading,避免整块闪「加载中」。
 */
async function refillMyApps() {
  try {
    const res = await listMyApps(1, 12)
    myApps.value = res.records
    myAppsTotal.value = res.total
  } catch {
    // 静默失败:保留删除后的本地列表,不打扰用户
  }
}

/** 删除我的应用:二次确认后调用后端,成功后从列表移除并立即从后端补充填满 */
function handleDeleteApp(app: MyApp) {
  Modal.confirm({
    title: '删除应用',
    content: `确定删除「${app.appName || '未命名应用'}」吗?代码文件与对话记录都会一并删除,无法恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await deleteApp(app.id)
        message.success('已删除')
        myApps.value = myApps.value.filter((a) => a.id !== app.id)
        myAppsTotal.value = Math.max(0, myAppsTotal.value - 1)
        // 立即从后端补充,删除后列表马上被新应用填满
        await refillMyApps()
      } catch (e) {
        message.error((e as Error).message || '删除失败')
      }
    },
  })
}

/** 查看代码弹窗状态 */
const codeModal = reactive<{
  open: boolean
  title: string
  loading: boolean
  files: { name: string; content: string }[]
  active: string
}>({
  open: false,
  title: '',
  loading: false,
  files: [],
  active: '',
})

/** 当前选中文件的语法高亮 HTML(highlight.js 输出,用 v-html 渲染) */
const activeCodeHtml = computed(() => {
  const f = codeModal.files.find((x) => x.name === codeModal.active)
  return f ? highlight(f.content, f.name) : ''
})

/** 构建产物/依赖目录前缀(历史脏数据兜底;后端 readProjectFiles 已跳过) */
const BUILD_ARTIFACT_PREFIXES = ['node_modules/', 'dist/', '.git/']
const isBuildArtifact = (p: string) => BUILD_ARTIFACT_PREFIXES.some((s) => p.startsWith(s))

/** 是否可与该应用对话:本人或管理员。广场里别人的应用只能查看代码/打开网站 */
function canChatApp(app: MyApp): boolean {
  if (!userStore.userInfo?.id) return false
  if (userStore.userInfo?.userRole === 'admin') return true
  return app.userId === userStore.userInfo.id
}

/** 打开「查看代码」弹窗:优先用后端的 files 清单(含 Vue 嵌套路径),无则回退 html/css/js */
async function openCode(app: MyApp) {
  codeModal.open = true
  codeModal.title = `「${app.appName || '未命名应用'}」的代码`
  codeModal.loading = true
  codeModal.files = []
  codeModal.active = ''
  try {
    const code = await getAppCode(app.id)
    const files: { name: string; content: string }[] = []
    if (code.files && code.files.length > 0) {
      // Vue / 通用:完整文件清单(path + content),tab 用相对路径;过滤构建产物目录
      for (const f of code.files) {
        if (f.path && f.content != null && !isBuildArtifact(f.path)) {
          files.push({ name: f.path, content: f.content })
        }
      }
    }
    if (files.length === 0) {
      if (code.htmlCode) files.push({ name: 'index.html', content: code.htmlCode })
      if (code.cssCode) files.push({ name: 'style.css', content: code.cssCode })
      if (code.jsCode) files.push({ name: 'script.js', content: code.jsCode })
    }
    codeModal.files = files
    codeModal.active = files[0]?.name ?? ''
  } catch (e) {
    message.error((e as Error).message || '获取代码失败')
  } finally {
    codeModal.loading = false
  }
}

/** 时间格式化 */
function fmtTime(t?: string): string {
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
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

/** 点击「立即生成」:每次输入都新建一个应用并进入它的对话页(不复用旧应用) */
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
    // 每次都是全新的对话/应用:先创建一个新 app,再跳转。
    // 快速开发与深度开发统一走对话页:边聊边生成,深度开发(Vue)在对话里实时生成项目
    const appId = await createApp(text.slice(0, 20), text, genMode.value)
    await router.push(`/chat/${appId}`)
  } catch (e) {
    message.error((e as Error).message || '创建应用失败,请重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="home">
    <!-- 1. Hero 标题区(终端风) -->
    <section class="hero">
      <div class="badge mono"><span class="prompt-sym">$</span> system.init("AI-Powered App Builder")</div>
      <h1 class="title">
        <ScrambleText text="一句话,生成你的" />
        <span class="gradient-text">专属应用</span><span class="cursor-blink">▋</span>
      </h1>
      <p class="subtitle mono">&gt; 描述需求,AI 自动完成设计、代码与交互 —— 零门槛,几分钟上线</p>
    </section>

    <!-- 2. 生成器:终端窗口 -->
    <section class="generator">
      <div class="term-shell">
        <div class="term-bar">
          <span class="term-dot red" />
          <span class="term-dot yellow" />
          <span class="term-dot green" />
          <span class="term-title">user@matrix:~$ ./generate --app</span>
        </div>
        <div class="input-panel">
          <div class="boot-lines mono">
            <LoopScramble :phrases="['build.desc', 'render:live', 'stream:on', 'ready.']" />
          </div>
          <div class="input-prompt mono"><span class="prompt-sym">$</span> <span class="typing-line">./ai_build --desc</span></div>
          <a-textarea
            v-model:value="requirement"
            class="requirement-input"
            :rows="3"
            placeholder="> 描述你的应用... 例如:帮我做一个每日打卡签到的应用,记录连续坚持天数"
            :maxlength="200"
            show-count
            @press-enter="handleGenerate"
          />
          <div class="mode-row">
            <a-segmented
              v-model:value="genMode"
              :options="[
                { label: '快速开发', value: 'html' },
                { label: '深度开发', value: 'vue' },
              ]"
            >
              <!-- 字符串解密动画替代 emoji 图标:只让「当前选中项」做解密动画,
                   未选中项保持静态文字——切换时仅新选中项解密、旧选中项即时落定,
                   避免两个标签同时乱码闪烁;更短的时长让切换更跟手丝滑 -->
              <template #label="{ value }">
                <span class="seg-label" :class="{ active: value === genMode }">
                  <ScrambleText
                    v-if="value === genMode"
                    :key="`sel-${genMode}`"
                    :text="value === 'html' ? '快速开发' : '深度开发'"
                    :duration="600"
                  />
                  <span v-else class="seg-static">{{ value === 'html' ? '快速开发' : '深度开发' }}</span>
                </span>
              </template>
            </a-segmented>
            <span class="mode-hint mono">{{ genMode === 'vue'
              ? '生成完整 Vue3 项目(文件数/token 有硬限制),耗时较长,完成后可部署预览'
              : '原生 HTML 单页,秒级出结果' }}</span>
          </div>
          <div class="input-footer">
            <span class="hint mono">// 试试:打卡 / 记账 / 待办清单</span>
            <button class="glow-btn" :disabled="submitting" @click="handleGenerate">
              {{ submitting ? '[ RUNNING… ]' : '[ EXECUTE ]' }}
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
        <span v-if="myAppsTotal" class="section-count">{{ myAppsTotal }} 个</span>
        <span class="section-more mono" @click="router.push('/conversations')">查看全部 →</span>
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
          <!-- 右上角弱化删除:默认半透明,悬浮卡片才显现,误触成本低 -->
          <button
            type="button"
            class="app-delete"
            title="删除应用"
            @click.stop="handleDeleteApp(app)"
          >✕</button>
          <div class="app-cover">
            <AppCover :name="app.appName" :cover="app.cover" :live="!!app.deployUrl" />
          </div>
          <div class="app-info">
            <div class="app-name">{{ app.appName || '未命名应用' }}</div>
            <div class="app-meta">{{ fmtTime(app.updateTime) }}</div>
          </div>
          <div class="card-actions" @click.stop>
            <button type="button" class="card-btn primary" @click="goChat(app)">💬 对话</button>
            <button v-if="app.deployUrl" type="button" class="card-btn" @click="openApp(app)">🌐 打开</button>
            <button type="button" class="card-btn" @click="openCode(app)">📄 查看代码</button>
            <button v-if="app.codeGenType === 'vue'" type="button" class="card-btn" @click="handleDownload(app)">⬇ 下载</button>
          </div>
        </div>
      </div>
    </section>

    <!-- 3.5 应用广场:全部已部署应用 -->
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
            <AppCover :name="app.appName" :cover="app.cover" :live="!!app.deployUrl" />
          </div>
          <div class="app-info">
            <div class="app-name">{{ app.appName || '未命名应用' }}</div>
            <div class="app-meta">{{ fmtTime(app.updateTime) }}</div>
          </div>
          <div class="card-actions" @click.stop>
            <button v-if="app.deployUrl" type="button" class="card-btn" @click="openApp(app)">🌐 打开</button>
            <button v-if="canChatApp(app)" type="button" class="card-btn primary" @click="goChat(app)">💬 对话</button>
            <button type="button" class="card-btn" @click="openCode(app)">📄 查看代码</button>
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

    <!-- 查看代码弹窗 -->
    <a-modal v-model:open="codeModal.open" :title="codeModal.title" width="780" :footer="null" destroy-on-close>
      <div class="code-viewer">
        <div v-if="codeModal.loading" class="code-state">正在读取代码...</div>
        <div v-else-if="codeModal.files.length === 0" class="code-state">
          这个应用还没生成过代码,去对话里和 AI 聊一聊,让它生成吧 ✨
        </div>
        <template v-else>
          <div class="code-tabs">
            <button
              v-for="f in codeModal.files"
              :key="f.name"
              type="button"
              class="code-tab"
              :class="{ active: codeModal.active === f.name }"
              @click="codeModal.active = f.name"
            >{{ f.name }}</button>
          </div>
          <pre class="code-block" v-html="activeCodeHtml"></pre>
        </template>
      </div>
    </a-modal>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 56px;
}

/* ---------- Hero(终端风) ---------- */
.hero {
  text-align: center;
  padding-top: 28px;
  animation: fade-up 0.6s ease both;
}

.badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 16px;
  border: 1px solid rgba(0, 255, 157, 0.35);
  background: rgba(0, 255, 157, 0.06);
  color: var(--primary);
  font-size: 12px;
  border-radius: 999px;
  margin-bottom: 20px;
}

.prompt-sym {
  color: var(--primary);
  font-weight: 700;
  text-shadow: 0 0 4px rgba(0, 255, 157, 0.4);
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
  max-width: 520px;
  color: var(--text-2);
  font-size: 15px;
  line-height: 1.7;
}

/* ---------- 生成器:终端窗口 ---------- */
.generator {
  animation: fade-up 0.6s ease 0.1s both;
}

.term-shell {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
  box-shadow: var(--shadow);
}

.input-panel {
  padding: 18px 22px 16px;
  background:
    linear-gradient(rgba(0, 255, 157, 0.025) 1px, transparent 1px),
    linear-gradient(90deg, rgba(0, 255, 157, 0.025) 1px, transparent 1px);
  background-size: 24px 24px;
}

.input-prompt {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-2);
  margin-bottom: 10px;
}

/* 终端状态行(循环解密小动画,取代原来的 [ OK ] 启动日志文字) */
.boot-lines {
  margin-bottom: 10px;
  min-height: 14px;
  display: flex;
  align-items: center;
}

/* 提示符命令打字机(一次性,结束后定格) */
.typing-line {
  display: inline-block;
  overflow: hidden;
  white-space: nowrap;
  animation: type-in 1.1s steps(16) both;
}

@keyframes type-in {
  from {
    width: 0;
  }
  to {
    width: 100%;
  }
}

.requirement-input {
  font-size: 16px;
  line-height: 1.7;
  background: rgba(0, 0, 0, 0.3) !important;
  border: 1px solid var(--border) !important;
  border-radius: 8px !important;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

.requirement-input:focus {
  border-color: rgba(0, 255, 157, 0.5) !important;
  box-shadow: 0 0 0 2px rgba(0, 255, 157, 0.12) !important;
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

/* 生成模式选择行(快速开发 / 深度开发) */
.mode-row {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 14px;
  flex-wrap: wrap;
}

.mode-hint {
  color: var(--text-3);
  font-size: 12px;
}

/* 把 a-segmented 覆盖成终端风(绿底黑字 / 暗色面板) */
.mode-row :deep(.ant-segmented) {
  background: rgba(0, 0, 0, 0.35);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 3px;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 13px;
}

.mode-row :deep(.ant-segmented-item) {
  color: var(--text-2);
  border-radius: 6px;
}

.mode-row :deep(.ant-segmented-item-selected) {
  background: var(--primary);
  color: #02110a;
  font-weight: 700;
}

.mode-row :deep(.ant-segmented-item:hover:not(.ant-segmented-item-selected)) {
  color: var(--primary);
}

/* 模式标签:未选中保持静态,选中项才做解密动画;选中标签轻微淡入+下落,
   让「快速开发 ↔ 深度开发」切换不两个标签同时乱码,更丝滑 */
.seg-label {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 64px;
  line-height: 1;
  white-space: nowrap;
}

.seg-label.active {
  animation: seg-fade-in 0.45s ease;
}

@keyframes seg-fade-in {
  from {
    opacity: 0.35;
    transform: translateY(2px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 流光按钮由全局 .glow-btn 提供(绿渐变黑字终端感) */

/* ---------- 我的应用 ---------- */
.my-apps {
  animation: fade-up 0.6s ease 0.2s both;
}

.section-count {
  font-size: 12px;
  color: var(--text-3);
  margin-left: 2px;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

/* 「查看全部」入口 */
.section-more {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-2);
  cursor: pointer;
  padding: 4px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  transition: color 0.2s ease, border-color 0.2s ease, background 0.2s ease;
}

.section-more:hover {
  color: var(--primary);
  border-color: rgba(0, 255, 157, 0.5);
  background: rgba(0, 255, 157, 0.06);
}

.my-apps-state {
  padding: 28px 20px;
  text-align: center;
  color: var(--text-3);
  font-size: 14px;
  background: rgba(0, 0, 0, 0.2);
  border: 1px dashed var(--border);
  border-radius: var(--radius);
}

.app-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
}

/* 应用卡片 = 终端窗口 */
.app-card {
  position: relative;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.25s ease, border-color 0.25s ease, box-shadow 0.25s ease;
}

.app-card:hover {
  transform: translateY(-4px);
  border-color: rgba(0, 255, 157, 0.5);
  box-shadow: var(--glow);
}

/* 卡片应用名左上角终端提示符(>_ 应用名) */
.app-name::before {
  content: '>_ ';
  color: var(--primary);
  font-weight: 700;
}

.app-cover {
  position: relative;
  height: 120px;
  overflow: hidden;
  background: #060a10;
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
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

.app-meta {
  font-size: 12px;
  color: var(--text-3);
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

/* 卡片操作按钮条(等宽终端按钮) */
.card-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0 14px 14px;
}

.card-btn {
  /* 4 个按钮同时出现(已部署的 Vue 应用)时不能把按钮压扁/文字溢出:
     min-width:max-content 保证按钮至少内容宽度,空间不足时 flex-wrap 自动换行,
     而不是被 flex:1 压缩到文字穿出按钮或横向溢出卡片 */
  flex: 1 1 auto;
  min-width: max-content;
  white-space: nowrap;
  height: 32px;
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.25);
  color: var(--text-2);
  font-size: 12px;
  cursor: pointer;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  letter-spacing: 0.4px;
  transition: border-color 0.2s ease, color 0.2s ease, background 0.2s ease, box-shadow 0.2s ease;
}

.card-btn:hover {
  border-color: rgba(0, 255, 157, 0.5);
  color: var(--primary);
  background: rgba(0, 255, 157, 0.06);
  box-shadow: 0 0 6px rgba(0, 255, 157, 0.05);
}

.card-btn.primary {
  border-color: rgba(0, 255, 157, 0.5);
  color: var(--primary);
  background: rgba(0, 255, 157, 0.08);
  font-weight: 600;
}

.card-btn.primary:hover {
  background: rgba(0, 255, 157, 0.16);
}

/* 右上角弱化删除:小尺寸、半透明、悬浮卡片才明显,弱化存在感避免误触 */
.app-delete {
  position: absolute;
  top: 6px;
  right: 6px;
  z-index: 2;
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.4);
  color: rgba(255, 255, 255, 0.45);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  opacity: 0.45;
  transition: opacity 0.2s ease, color 0.2s ease, background 0.2s ease, border-color 0.2s ease;
}

.app-card:hover .app-delete,
.app-delete:focus-visible {
  opacity: 1;
  color: rgba(255, 255, 255, 0.75);
}

.app-delete:hover {
  color: #fca5a5;
  border-color: rgba(244, 63, 94, 0.55);
  background: rgba(244, 63, 94, 0.15);
}

/* 查看代码弹窗 */
.code-viewer {
  margin-top: 4px;
}

.code-state {
  padding: 48px 20px;
  text-align: center;
  color: var(--text-3);
  font-size: 14px;
}

.code-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 12px;
}

.code-tab {
  height: 30px;
  padding: 0 16px;
  border: 1px solid var(--border);
  border-radius: 8px 8px 0 0;
  background: rgba(0, 0, 0, 0.25);
  color: var(--text-2);
  font-size: 13px;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  cursor: pointer;
}

.code-tab.active {
  border-color: rgba(0, 255, 157, 0.35);
  background: rgba(0, 255, 157, 0.08);
  color: var(--primary);
}

.code-block {
  max-height: 60vh;
  overflow: auto;
  margin: 0;
  padding: 16px 18px;
  background: #060a10;
  border-radius: 10px;
  color: var(--text);
  font-size: 13px;
  line-height: 1.6;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
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
  box-shadow: 0 0 4px rgba(0, 255, 157, 0.2);
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
  border-color: rgba(0, 255, 157, 0.5);
  box-shadow: var(--glow);
}

.t-icon {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  font-size: 22px;
  background: rgba(0, 255, 157, 0.08);
  border: 1px solid var(--border);
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
  background: rgba(0, 0, 0, 0.2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 22px 20px;
  transition: border-color 0.25s ease, box-shadow 0.25s ease;
}

.feature-card:hover {
  border-color: rgba(0, 255, 157, 0.3);
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
