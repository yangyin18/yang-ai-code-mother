<script setup lang="ts">
/**
 * 生成页:对话式应用构建器(左对话 + 右应用面板)
 *
 *   ┌─────────────────────────────────────────────────────┐
 *   │  需求回顾「xxx」           ⏳ 62%          [步骤chips]   │ ← 头部
 *   ├───────────────────────────┬─────────────────────────┤
 *   │  我: 做一个打卡应用         │  [实时预览] [代码]  LIVE    │ ← 右面板 tabs
 *   │  AI: 正在「生成代码」…      │  ├─ 预览 iframe(实时)      │
 *   │                           │  └─ 代码(深色,完成后分文件) │
 *   │                           │  [⬇ 下载代码] [🚀 部署上线]  │ ← 操作栏
 *   └───────────────────────────┴─────────────────────────┘
 *
 * 进入页面自动启动 store.start():创建应用 → SSE 流式生成;
 * 左栏对话以气泡呈现 AI 状态,右栏实时预览 + 代码 + 部署/下载,生成完成即可上线。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { deployApp } from '@/api/generation'
import { useGenerationStore } from '@/stores/generation'
import { extractHtmlCode } from '@/utils/sseDisplay'
import { highlight } from '@/utils/highlight'
import { downloadCodeFiles } from '@/utils/download'
import type { CodeFile } from '@/types'

const router = useRouter()
const store = useGenerationStore()

/** 当前步骤下标 */
const current = computed(() => store.currentStep)

/** 某一步骤的状态:已过=done,当前=active,未到=pending */
const stepStatus = (i: number): 'done' | 'active' | 'pending' =>
  i < current.value ? 'done' : i === current.value ? 'active' : 'pending'

/** 是否全部完成 */
const allDone = computed(() => store.currentStep >= store.steps.length)

/** 进度百分比 */
const percent = computed(() =>
  Math.round((store.currentStep / store.steps.length) * 100),
)

/** 连接是否已建立 */
const streamConnected = computed(() => store.streamConnected)

/** 是否已开始输出代码(原始流有内容) */
const streaming = computed(() => store.streamingCode.length > 0)

/** 从连接建立起的已等待秒数(每 1s 刷新) */
const elapsedSec = ref(0)
let elapsedTimer: ReturnType<typeof setInterval> | null = null

/** 离开页面时取消 SSE 流的信号 */
const abortCtrl = new AbortController()

// ==================== 左栏:对话 ====================

/** 对话区引用(新消息自动滚到底) */
const bodyRef = ref<HTMLElement | null>(null)

/** AI 状态气泡文案:连接中 → 已连接等待 → 流式生成中 → 完成(完成态不走这里) */
const statusText = computed(() => {
  if (allDone.value) return ''
  if (!streamConnected.value) return '正在连接 AI…'
  if (!streaming.value) return `已连接,AI 正在生成应用…(已等待 ${elapsedSec.value}s)`
  const step = store.steps[Math.min(current.value, store.steps.length - 1)]
  return `正在「${step?.title ?? '生成'}」…(已接收 ${store.rawReceived} 字符)`
})

watch([statusText, allDone], async () => {
  await nextTick()
  if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
})

// ==================== 右栏:应用面板 ====================

/** 面板标签:预览 / 代码 */
const activeTab = ref<'preview' | 'code'>('preview')

/** 流式清洗后的代码(展示 + 预览) */
const displayCode = computed(() => extractHtmlCode(store.streamingCode))

/** 流式代码区滚动容器(自动滚到底部,最新代码始终可见) */
const streamCodeRef = ref<HTMLElement | null>(null)

watch(displayCode, () => {
  if (streamCodeRef.value) {
    const el = streamCodeRef.value
    el.scrollTop = el.scrollHeight
  }
})

/** 流式中切到「代码」tab 时,直接跳到最新代码位置 */
watch(activeTab, async (tab) => {
  if (tab === 'code' && store.streamingCode) {
    await nextTick()
    if (streamCodeRef.value) streamCodeRef.value.scrollTop = streamCodeRef.value.scrollHeight
  }
})

/** 预览 iframe 内容:流式期间节流刷新,避免每 token 重建 iframe */
const previewHtml = ref('')
let previewTimer: ReturnType<typeof setInterval> | null = null

function refreshPreview() {
  previewHtml.value = displayCode.value
}

/** 代码文件列表(完成后由 app.html/css/js 拼出,供文件 tabs 与下载) */
const activeFileKey = ref('')
const files = computed<CodeFile[]>(() => {
  if (!store.app) return []
  const names = store.app.fileNames ?? []
  const contents: Record<string, string> = {
    'index.html': store.app.htmlCode,
    'style.css': store.app.cssCode,
    'script.js': store.app.jsCode,
  }
  const list: CodeFile[] = []
  for (const n of names) {
    const content = contents[n]
    if (content) list.push({ name: n, content })
  }
  if (list.length > 0) return list
  if (store.app.htmlCode) list.push({ name: 'index.html', content: store.app.htmlCode })
  if (store.app.cssCode) list.push({ name: 'style.css', content: store.app.cssCode })
  if (store.app.jsCode) list.push({ name: 'script.js', content: store.app.jsCode })
  return list
})

/** 完成后默认选中第一个文件 */
watch(files, (list) => {
  const first = list[0]
  if (first && !activeFileKey.value) activeFileKey.value = first.name
})

const activeFile = computed(
  () => files.value.find((f) => f.name === activeFileKey.value) ?? null,
)

/** 高亮后的代码(用 v-html 渲染) */
const highlightedCode = computed(() =>
  activeFile.value ? highlight(activeFile.value.content, activeFile.value.name) : '',
)

/**
 * 预览文档:流式中用节流清洗后的 html;完成后若含 css/js 则注入到对应位置保证独立渲染。
 */
const previewDoc = computed(() => {
  if (!store.app) return previewHtml.value
  // 提前取出到局部常量,供 replace 回调闭包使用(避免 TS 对 store.app 的收窄失效)
  const html = store.app.htmlCode
  const css = store.app.cssCode
  const js = store.app.jsCode
  let doc = html
  if (css && !/<style/i.test(doc)) {
    doc = doc.replace(/<head([^>]*)>/i, (_m, attrs) => {
      return `<head${attrs}>\n<style>${css}<\/style>`
    })
  }
  if (js && !/<script/i.test(doc)) {
    doc = doc.replace(/<\/body>/i, `<script>${js}<\/script>\n<\/body>`)
  }
  return doc
})

/** 部署中标记 */
const deploying = ref(false)

/** 下载全部代码(单文件按原扩展名下载,多文件拼接为 txt) */
function handleDownload() {
  if (!store.app) return
  downloadCodeFiles(store.app.name, files.value)
}

/** 部署上线:未部署则调后端发布到 nginx,已部署则直接打开站点 */
async function handleDeploy() {
  if (!store.app) return
  if (store.app.deployUrl) {
    window.open(store.app.deployUrl, '_blank')
    return
  }
  deploying.value = true
  try {
    const url = await deployApp(store.app.appId)
    store.app.deployUrl = url
    message.success('部署成功,已发布到 nginx')
    window.open(url, '_blank')
  } catch {
    // 错误已由 request 拦截器统一提示
  } finally {
    deploying.value = false
  }
}

// ==================== 生命周期 ====================

function startElapsedTimer() {
  if (elapsedTimer) return
  elapsedTimer = setInterval(() => {
    if (store.streamStartedAt) {
      elapsedSec.value = Math.floor((Date.now() - store.streamStartedAt) / 1000)
    }
  }, 1000)
}

function stopElapsedTimer() {
  if (elapsedTimer) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
}

onMounted(async () => {
  if (!store.requirement.trim()) {
    router.replace('/')
    return
  }
  if (store.currentStep === 0) {
    // 流式期间节流刷新预览(400ms),代码文本区每 chunk 即时更新
    previewTimer = setInterval(() => refreshPreview(), 400)
    startElapsedTimer()
    try {
      await store.start(abortCtrl.signal)
      stopElapsedTimer()
    } catch (e) {
      stopElapsedTimer()
      if (abortCtrl.signal.aborted) {
        // 用户主动离开/取消:不重置状态,交给下一个页面处理
        return
      }
      // 生成失败(未登录 / AI 异常等):清空状态回首页,错误已由拦截器提示
      store.reset()
      router.replace('/')
    } finally {
      if (previewTimer) {
        clearInterval(previewTimer)
        previewTimer = null
      }
      refreshPreview()
    }
  } else {
    refreshPreview()
  }
})

onBeforeUnmount(() => {
  // 离开页面(返回/关闭)时取消 SSE,避免后端残留流占用连接
  abortCtrl.abort()
  if (previewTimer) {
    clearInterval(previewTimer)
    previewTimer = null
  }
  stopElapsedTimer()
})

// 调试钩子:暴露生成状态供端到端测试读取(读取即最新值,不影响运行时)
;(window as any).__genDebug = {
  get state() {
    return {
      step: store.currentStep,
      cur: current.value,
      stepsLen: store.steps.length,
      hasApp: !!store.app,
      appName: store.app?.name ?? null,
      done: allDone.value,
      req: store.requirement,
      tab: activeTab.value,
    }
  },
}
</script>

<template>
  <div class="gen">
    <!-- 头部:需求回顾 + 状态 + 步骤 -->
    <header class="gen-head">
      <div class="req-line">
        <span class="req-label">正在为以下需求生成应用</span>
        <span class="req-text">「{{ store.requirement }}」</span>
        <span class="req-percent">{{ percent }}%</span>
      </div>

      <div class="status-line">
        <span v-if="!allDone" class="status live">
          <i class="live-dot" />
          {{ statusText }}
        </span>
        <span v-else class="status ok">✨ 生成完成</span>
        <div class="steps">
          <span
            v-for="(s, i) in store.steps"
            :key="s.key"
            class="step"
            :class="'s-' + stepStatus(i)"
          >
            <i class="step-dot">{{ stepStatus(i) === 'done' ? '✓' : '' }}</i>
            {{ s.title }}
          </span>
        </div>
      </div>
    </header>

    <!-- 左对话 + 右应用面板 -->
    <div class="gen-main">
      <!-- 左:对话区 -->
      <div ref="bodyRef" class="gen-body">
        <div class="msg-row user">
          <div class="avatar">我</div>
          <div class="bubble-wrap">
            <div class="bubble">{{ store.requirement }}</div>
          </div>
        </div>

        <div class="msg-row ai">
          <div class="avatar">AI</div>
          <div class="bubble-wrap">
            <div class="bubble">
              <template v-if="!allDone">{{ statusText }}<span class="typing">▋</span></template>
              <template v-else>
                ✨ 生成完成!应用「{{ store.app?.name || '你的应用' }}」已生成,右侧可预览、部署或下载代码
              </template>
            </div>
          </div>
        </div>
      </div>

      <!-- 右:应用面板 -->
      <aside class="preview-pane">
        <div class="pane-head">
          <div class="tabs">
            <button
              type="button"
              class="tab"
              :class="{ active: activeTab === 'preview' }"
              @click="activeTab = 'preview'"
            >
              实时预览
            </button>
            <button
              type="button"
              class="tab"
              :class="{ active: activeTab === 'code' }"
              @click="activeTab = 'code'"
            >
              代码
            </button>
          </div>
          <span v-if="streaming || allDone" class="live-badge">LIVE</span>
        </div>

        <!-- 预览 tab -->
        <iframe
          v-if="activeTab === 'preview'"
          class="frame"
          :srcdoc="previewDoc"
          sandbox="allow-scripts allow-modals"
          title="实时预览"
        />

        <!-- 代码 tab -->
        <div v-else class="code-pane">
          <template v-if="allDone && files.length">
            <div class="file-tabs">
              <button
                v-for="f in files"
                :key="f.name"
                type="button"
                class="file-tab"
                :class="{ active: activeFileKey === f.name }"
                @click="activeFileKey = f.name"
              >
                {{ f.name }}
              </button>
            </div>
            <pre class="code-block" v-html="highlightedCode"></pre>
          </template>
          <pre ref="streamCodeRef" v-else class="code-block plain">{{ displayCode || '// AI 正在生成代码,请稍候…' }}</pre>
        </div>

        <!-- 操作栏 -->
        <div class="pane-actions">
          <a-button size="small" :disabled="!allDone || !store.app" @click="handleDownload">
            ⬇ 下载代码
          </a-button>
          <a-button
            size="small"
            type="primary"
            :loading="deploying"
            :disabled="!allDone || !store.app"
            @click="handleDeploy"
          >
            {{ store.app?.deployUrl ? '🔗 打开网站' : '🚀 部署上线' }}
          </a-button>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.gen {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: calc(100vh - 190px);
  min-height: 480px;
  max-width: 1120px;
  margin: 0 auto;
  animation: fade-up 0.4s ease both;
}

/* ---------- 头部 ---------- */
.gen-head {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 14px 18px;
  box-shadow: var(--shadow);
  display: flex;
  flex-direction: column;
  gap: 10px;
  flex-shrink: 0;
}

.req-line {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

.req-label {
  font-size: 12px;
  color: var(--text-2);
}

.req-text {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}

.req-percent {
  margin-left: auto;
  font-size: 15px;
  font-weight: 800;
  background: var(--gradient);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.status-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.status {
  font-size: 13px;
}

.status.live {
  color: var(--success);
}

.live-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--success);
  margin-right: 6px;
  animation: blink 1.2s infinite;
}

.status.ok {
  color: var(--success);
}

/* 步骤 chips */
.steps {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.step {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid var(--border);
  color: var(--text-3);
}

.step-dot {
  width: 13px;
  height: 13px;
  border-radius: 50%;
  border: 1px solid currentColor;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 9px;
  font-style: normal;
  color: transparent;
}

.step.s-active {
  color: #6366f1;
  border-color: rgba(99, 102, 241, 0.55);
  background: rgba(99, 102, 241, 0.1);
}

.step.s-active .step-dot {
  border-color: #6366f1;
  animation: blink 1.2s infinite;
}

.step.s-done {
  color: var(--success);
  border-color: rgba(52, 211, 153, 0.4);
}

.step.s-done .step-dot {
  border-color: var(--success);
  color: var(--success);
}

/* ---------- 主体:左对话 + 右面板 ---------- */
.gen-main {
  flex: 1;
  display: grid;
  grid-template-columns: minmax(380px, 46%) 1fr;
  gap: 16px;
  min-height: 0;
}

/* 左:对话区 */
.gen-body {
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px 4px 8px;
}

.msg-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.avatar {
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  background: var(--gradient);
}

/* 用户消息右对齐 */
.msg-row.user {
  flex-direction: row-reverse;
}

.bubble-wrap {
  max-width: 78%;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.msg-row.user .bubble-wrap {
  align-items: flex-end;
}

.bubble {
  padding: 10px 14px;
  border-radius: 14px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  color: var(--text);
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.msg-row.user .bubble {
  background: var(--gradient);
  color: #fff;
  border: none;
}

.typing {
  display: inline-block;
  animation: blink 1s steps(2, start) infinite;
  color: inherit;
}

/* 右:应用面板 */
.preview-pane {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
  box-shadow: var(--shadow);
}

.pane-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 14px;
  border-bottom: 1px solid var(--border);
  background: rgba(21, 27, 38, 0.02);
  flex-shrink: 0;
}

.tabs {
  display: flex;
  gap: 4px;
}

.tab {
  border: none;
  background: transparent;
  padding: 5px 14px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--text-2);
  cursor: pointer;
  transition: all 0.2s;
}

.tab:hover {
  color: var(--text);
  background: rgba(21, 27, 38, 0.05);
}

.tab.active {
  color: #fff;
  background: var(--gradient);
  font-weight: 600;
}

.live-badge {
  font-size: 10px;
  color: var(--success);
  border: 1px solid rgba(52, 211, 153, 0.4);
  background: rgba(52, 211, 153, 0.08);
  border-radius: 6px;
  padding: 1px 7px;
}

.frame {
  flex: 1;
  width: 100%;
  border: none;
  background: #fff;
}

/* 代码面板(终端深色,统一全局 hljs 暗色 token) */
.code-pane {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #060a10;
}

.file-tabs {
  display: flex;
  gap: 2px;
  padding: 8px 10px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  flex-shrink: 0;
}

.file-tab {
  border: 1px solid transparent;
  border-bottom: none;
  background: transparent;
  color: #9ca3af;
  padding: 5px 12px;
  border-radius: 8px 8px 0 0;
  font-size: 12px;
  cursor: pointer;
}

.file-tab:hover {
  color: #d4d4d4;
}

.file-tab.active {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}

.code-block {
  flex: 1;
  margin: 0;
  padding: 14px 16px;
  overflow: auto;
  color: #d4d4d4;
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.65;
  white-space: pre;
}

.code-block.plain {
  color: #d4d4d4;
}

/* 操作栏 */
.pane-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 10px 14px;
  border-top: 1px solid var(--border);
  background: var(--panel);
  flex-shrink: 0;
}

/* ---------- 响应式 ---------- */
@media (max-width: 900px) {
  .gen-main {
    grid-template-columns: 1fr;
  }
  .preview-pane {
    display: none; /* 小屏隐藏面板,对话占满 */
  }
}

/* ---------- 动画 ---------- */
@keyframes fade-up {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes blink {
  to {
    visibility: hidden;
  }
}
</style>
