<script setup lang="ts">
/**
 * Vue 深度开发项目页(/project/:appId)
 *
 * 与快速开发的对话流不同,Vue 项目由模型通过 writeFile 工具逐个落盘:
 *   - 左侧终端窗:实时日志 —— 连接状态 / 生成计划流式文本 / 每个文件写入成功即追加「✓ 已生成 <path>」
 *   - 右侧文件树:实时文件清单(只显示路径,不显示代码内容 —— 深度模式刻意不向前端暴露代码,省 token/传输)
 * 完成后:显示项目描述 + 文件数,提供「下载 ZIP」「重新生成」「返回首页」。
 * 深度模式代码内容不进前端,「查看代码」由首页卡片走 /app/code 从磁盘读取(不消耗 AI token)。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { deployAppStream, downloadAppZip, generateVueProjectStream, getAppCode, getAppDetail, type AppVO } from '@/api/generation'

type Phase = 'connecting' | 'streaming' | 'done' | 'error'

/** 状态徽标文案(模板里按 phase 取值) */
const phaseBadgeText: Record<Phase, string> = {
  connecting: 'CONNECTING',
  streaming: 'GENERATING',
  done: 'DONE',
  error: 'ERROR',
}

const route = useRoute()
const router = useRouter()

const appId = computed(() => String(route.params.appId ?? ''))

const appInfo = ref<AppVO | null>(null)
const requirement = ref('')
const phase = ref<Phase>('connecting')
const errorText = ref('')

/** 生成计划流式文本(message 事件增量拼接,非代码) */
const planText = ref('')
/** 已生成的文件路径清单(file 事件追加,只含路径) */
const filePaths = ref<string[]>([])

/** 构建产物/依赖目录前缀(后端 readProjectFiles 已跳过,这里是历史脏数据兜底) */
const SKIP_PREFIXES = ['node_modules/', 'dist/', '.git/']
const isBuildArtifact = (p: string) => SKIP_PREFIXES.some((s) => p.startsWith(s))

/** 过滤掉构建产物后的源码路径(文件树/数量统计用它,避免部署后 node_modules 淹没界面) */
const cleanFilePaths = computed(() => filePaths.value.filter((p) => !isBuildArtifact(p)))
/** 生成完成后的项目描述(来自 complete 事件) */
const description = ref('')
/** 已等待首 token 的毫秒数(heartbeat 更新) */
const waitingMs = ref(0)

/** 文件树:按 / 分段做缩进(只展示路径,不给内容) */
const treeLines = computed(() =>
  [...cleanFilePaths.value].sort().map((p) => ({
    path: p,
    depth: p.split('/').length - 1,
    name: p.split('/').pop() ?? p,
  })),
)

/** 等待时长文案(秒) */
const waitingText = computed(() => {
  const s = Math.floor(waitingMs.value / 1000)
  return `已等待 ${s}s`
})

const logRef = ref<HTMLElement | null>(null)

/** 自动滚到底部,让最新日志始终可见 */
function scrollToBottom() {
  requestAnimationFrame(() => {
    if (logRef.value) logRef.value.scrollTop = logRef.value.scrollHeight
  })
}

/** 当前生成请求的取消控制器(离开页面时 abort 中断流) */
let abortCtrl: AbortController | null = null

/** 部署结果访问地址(后端构建 dist 并发布到 nginx 后回填) */
const deployUrl = ref('')
/** 构建部署中(首次部署含 npm install,耗时较长) */
const deploying = ref(false)
/** 部署实时进度字符串(SSE progress 事件逐条刷新:阶段 + npm 输出) */
const deployMsg = ref('')

/**
 * 预览版本号:每次重新部署成功后 +1,并拼进 iframe src 的查询参数做缓存破除。
 * 否则重部署时 deployUrl 字符串不变,iframe :src 绑定不变 → Vue 不会重新加载 iframe,
 * 浏览器显示旧构建(部署成功但预览仍是上一次的页面)。
 */
const previewVersion = ref(0)
/** 实时预览地址 = 部署地址 + 缓存破除参数(iframe 专用;链接/新窗口仍用不带参数的 deployUrl) */
const previewSrc = computed(() => {
  if (!deployUrl.value) return ''
  const sep = deployUrl.value.includes('?') ? '&' : '?'
  return deployUrl.value + sep + 'v=' + previewVersion.value
})

/** 清空状态,重新开始一轮生成 */
function reset() {
  planText.value = ''
  filePaths.value = []
  description.value = ''
  errorText.value = ''
  waitingMs.value = 0
  deployUrl.value = ''
  deployMsg.value = ''
  phase.value = 'connecting'
}

/** 部署上线:后端先 npm 构建 Vue 项目,再把 dist 发布到 nginx,返回访问地址(全程显示真实进度字符串) */
async function handleDeploy() {
  if (deploying.value) return
  deploying.value = true
  deployMsg.value = ''
  try {
    deployUrl.value = await deployAppStream(appId.value, {
      onProgress: (msg) => {
        // SSE progress 事件:部署阶段 + npm 输出逐行刷新
        deployMsg.value = msg
      },
    })
    deployMsg.value = ''
    // 版本号 +1 → iframe src 变化 → 立即加载最新构建(避免同 URL 不刷新导致预览 stale)
    previewVersion.value += 1
    message.success('✓ 已部署上线,可在下方预览')
  } catch (e) {
    message.error((e as Error).message || '部署失败,请重新生成后再试')
  } finally {
    deploying.value = false
  }
}

/** 新窗口打开已部署的站点 */
function openDeployUrl() {
  if (deployUrl.value) window.open(deployUrl.value, '_blank')
}

/**
 * 应用已有生成产物时,直接加载展示(文件树 + 已部署预览),不重新生成。
 * 返回是否已存在产物:存在 → 进入 done 态;不存在 → 调用方走首次生成。
 */
async function loadExistingProject(): Promise<boolean> {
  try {
    const res = await getAppCode(appId.value)
    if (res.fileNames?.length) {
      filePaths.value = res.fileNames
      description.value = appInfo.value?.initPrompt || ''
      deployUrl.value = res.deployUrl || ''
      phase.value = 'done'
      return true
    }
  } catch {
    // 读取失败按无产物处理,走首次生成
  }
  return false
}

/** 开始生成:复用应用的 initPrompt 作为需求 */
async function startGenerate() {
  reset()
  abortCtrl = new AbortController()
  const controller = abortCtrl
  try {
    await generateVueProjectStream(appId.value, requirement.value, {
      signal: controller.signal,
      onStarted: () => {
        if (controller.signal.aborted) return
        phase.value = 'streaming'
        scrollToBottom()
      },
      onHeartbeat: (ms) => {
        if (!controller.signal.aborted) waitingMs.value = ms
      },
      onPartial: (chunk) => {
        if (controller.signal.aborted) return
        // message 事件是模型的生成计划/收尾文本,直接追加到计划区
        planText.value += chunk
        scrollToBottom()
      },
      onFile: (path) => {
        if (controller.signal.aborted) return
        // 每个文件落盘:记录路径,左侧日志即时追加一行(只展示路径)
        filePaths.value.push(path)
        scrollToBottom()
      },
    }).then((result) => {
      // complete 已到达:用后端权威的文件清单与描述补全(深度模式只保留路径与描述,不展示代码)
      if (controller.signal.aborted) return
      if (result.fileNames?.length) filePaths.value = result.fileNames
      description.value = result.description || ''
    })
    // 上述 then 在流结束后同步执行,await 返回后再标记完成
    if (controller.signal.aborted) return
    phase.value = 'done'
    scrollToBottom()
  } catch (e) {
    if (controller.signal.aborted) return
    phase.value = 'error'
    errorText.value = (e as Error).message || '生成失败,请重试'
  }
}

/** 下载整个 Vue 项目为 ZIP */
async function handleDownload() {
  try {
    await downloadAppZip(appId.value)
  } catch (e) {
    message.error((e as Error).message || '下载失败')
  }
}

function goBack() {
  router.push('/')
}

onMounted(async () => {
  // 校验应用存在且确实是 Vue 深度开发:html 应用直接退回对话页
  try {
    appInfo.value = await getAppDetail(appId.value)
  } catch {
    message.error('应用不存在')
    router.replace('/')
    return
  }
  if (appInfo.value?.codeGenType !== 'vue') {
    router.replace(`/chat/${appId.value}`)
    return
  }
  requirement.value = appInfo.value?.initPrompt || ''
  // 已有生成产物:直接展示上次的成果,不重新生成(点「重新生成」按钮才再跑 AI)
  const hasExisting = await loadExistingProject()
  if (!hasExisting) {
    if (requirement.value) {
      await startGenerate()
    } else {
      phase.value = 'error'
      errorText.value = '该应用没有初始需求描述,无法生成'
    }
  }
})

onBeforeUnmount(() => {
  abortCtrl?.abort()
})
</script>

<template>
  <div class="project-page">
    <!-- 顶部:返回 + 应用名 + 状态徽标 -->
    <div class="project-head">
      <button type="button" class="back-btn mono" @click="goBack">← 返回</button>
      <h2 class="project-title">{{ appInfo?.appName || 'Vue 项目' }}</h2>
      <span class="phase-badge" :class="phase">{{ phaseBadgeText[phase] }}</span>
    </div>

    <div class="project-grid">
      <!-- 左栏:终端实时日志 -->
      <div class="term-shell">
        <div class="term-bar">
          <span class="term-dot red" />
          <span class="term-dot yellow" />
          <span class="term-dot green" />
          <span class="term-title">vue-build --app={{ appId }}</span>
        </div>
        <div ref="logRef" class="term-body mono">
          <div class="log-line dim">&gt; 需求:{{ requirement || '(空)' }}</div>
          <div v-if="phase === 'connecting'" class="log-line">
            &gt; 连接 AI,等待生成计划<span v-if="waitingMs" class="dim">… {{ waitingText }}</span>
            <span class="cursor-blink">▋</span>
          </div>

          <!-- 生成计划流式文本 -->
          <div v-if="planText" class="plan-area">
            <div class="plan-content">{{ planText }}<span v-if="phase === 'streaming'" class="cursor-blink">▋</span></div>
          </div>

          <!-- 文件写入日志 -->
          <div v-for="(p, i) in filePaths" :key="i" class="log-line ok">✓ 已生成 {{ p }}</div>

          <!-- 部署进度:真实阶段 + npm 输出逐行显示 -->
          <div v-if="deploying" class="log-line">
            <span class="dim">> 部署: </span><span>{{ deployMsg || '连接部署服务…' }}</span>
            <span class="cursor-blink">▋</span>
          </div>

          <!-- 完成态 -->
          <div v-if="phase === 'done'" class="log-line ok">✓ 全部文件生成完毕({{ cleanFilePaths.length }} 个)</div>
          <div v-if="phase === 'done' && description" class="log-line dim">&gt; 说明:{{ description }}</div>

          <!-- 异常态 -->
          <div v-if="phase === 'error'" class="log-line err">✗ {{ errorText }}</div>
        </div>
      </div>

      <!-- 右栏:实时文件树(只显示路径,不显示内容) -->
      <div class="tree-pane">
        <div class="pane-head mono">PROJECT FILES — {{ cleanFilePaths.length }}</div>
        <div class="tree-body mono">
          <div v-if="cleanFilePaths.length === 0" class="tree-empty">
            {{ phase === 'error' ? '生成失败,没有产生文件' : '等待文件写入…' }}
          </div>
          <div
            v-for="(line, i) in treeLines"
            :key="i"
            class="tree-line"
            :style="{ paddingLeft: `${12 + line.depth * 16}px` }"
          >
            <span class="tree-branch">{{ line.depth === 0 ? '└─' : '├─' }}</span>
            <span class="tree-name" :class="{ root: line.depth === 0 }">{{ line.name }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部操作栏:下载 / 部署上线 / 重新生成(完成态可用) -->
    <div class="project-actions">
      <button type="button" class="action-btn primary mono" :disabled="phase !== 'done'" @click="handleDownload">
        ⬇ 下载项目 ZIP
      </button>
      <button
        type="button"
        class="action-btn deploy mono"
        :class="{ 'btn-loading': deploying }"
        :disabled="phase !== 'done' || deploying"
        @click="handleDeploy"
      >
        <span v-if="deploying" class="spinner" />
        > 部署上线
      </button>
      <button type="button" class="action-btn mono" :disabled="phase === 'streaming' || phase === 'connecting'" @click="startGenerate">
        🔄 重新生成
      </button>
      <button type="button" class="action-btn mono" @click="goBack">← 返回首页</button>
    </div>

    <!-- 已部署:内嵌 iframe 实时预览 + 新窗口打开 -->
    <div v-if="deployUrl" class="deploy-preview">
      <div class="pane-head mono">
        LIVE PREVIEW
        <a :href="deployUrl" target="_blank" class="preview-link">{{ deployUrl }}</a>
        <span class="preview-open mono" @click="openDeployUrl">↗ 新窗口打开</span>
      </div>
      <iframe :src="previewSrc" class="preview-frame" title="部署预览" />
    </div>
  </div>
</template>

<style scoped>
.project-page {
  max-width: 1120px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: calc(100vh - 190px);
  min-height: 480px;
}

/* ---------- 顶栏 ---------- */
.project-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.back-btn {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.25);
  color: var(--text-2);
  padding: 6px 14px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.18s;
}

.back-btn:hover {
  color: var(--primary);
  border-color: rgba(0, 255, 157, 0.5);
}

.project-title {
  flex: 1;
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.phase-badge {
  font-size: 11px;
  padding: 3px 10px;
  border-radius: 6px;
  border: 1px solid var(--border-strong);
  color: var(--text-2);
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  letter-spacing: 0.5px;
}

.phase-badge.streaming {
  color: var(--primary);
  border-color: rgba(0, 255, 157, 0.4);
  background: rgba(0, 255, 157, 0.08);
  animation: blink 1.2s steps(2, start) infinite;
}

.phase-badge.done {
  color: var(--success);
  border-color: rgba(52, 211, 153, 0.4);
}

.phase-badge.error {
  color: #f59e0b;
  border-color: rgba(245, 158, 11, 0.4);
}

/* ---------- 主体两栏 ---------- */
.project-grid {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 16px;
}

/* 左栏终端 */
.term-shell {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
  box-shadow: var(--shadow);
}

.term-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--border);
  background: rgba(0, 0, 0, 0.25);
  flex-shrink: 0;
}

.term-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.term-dot.red {
  background: #ff5f56;
}

.term-dot.yellow {
  background: #ffbd2e;
}

.term-dot.green {
  background: #27c93f;
}

.term-title {
  margin-left: 8px;
  font-size: 11px;
  color: var(--text-3);
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

.term-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 16px;
  background:
    linear-gradient(rgba(0, 255, 157, 0.02) 1px, transparent 1px),
    linear-gradient(90deg, rgba(0, 255, 157, 0.02) 1px, transparent 1px);
  background-size: 24px 24px;
  font-size: 13px;
  line-height: 1.7;
}

.log-line {
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-word;
}

.log-line.dim {
  color: var(--text-2);
}

.log-line.ok {
  color: var(--success);
}

.log-line.err {
  color: #f59e0b;
}

.plan-area {
  margin-top: 8px;
}

.plan-content {
  color: var(--text-2);
  white-space: pre-wrap;
  word-break: break-word;
  border-left: 2px solid rgba(0, 255, 157, 0.35);
  padding-left: 12px;
}

/* 右栏文件树 */
.tree-pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}

.pane-head {
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  font-size: 12px;
  color: var(--primary);
  background: rgba(0, 255, 157, 0.04);
  flex-shrink: 0;
}

.tree-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 0;
  font-size: 12px;
}

.tree-empty {
  padding: 40px 20px;
  text-align: center;
  color: var(--text-3);
}

.tree-line {
  padding-top: 3px;
  padding-bottom: 3px;
  padding-right: 10px;
  color: var(--text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tree-branch {
  color: var(--text-3);
  margin-right: 6px;
}

.tree-name {
  color: var(--text-2);
}

.tree-name.root {
  color: var(--text);
  font-weight: 600;
}

/* ---------- 底部操作 ---------- */
.project-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  flex-shrink: 0;
}

.action-btn {
  height: 34px;
  padding: 0 16px;
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.25);
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.18s;
}

.action-btn:hover:not(:disabled) {
  border-color: rgba(0, 255, 157, 0.5);
  color: var(--primary);
}

.action-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.action-btn.primary {
  border-color: rgba(0, 255, 157, 0.45);
  color: var(--primary);
  background: rgba(0, 255, 157, 0.07);
}

.action-btn.deploy {
  border-color: rgba(56, 189, 248, 0.45);
  color: #38bdf8;
  background: rgba(56, 189, 248, 0.07);
}

.action-btn.deploy:hover:not(:disabled) {
  border-color: rgba(56, 189, 248, 0.7);
  color: #7dd3fc;
}

/* ---------- 部署预览 ---------- */
.deploy-preview {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}

.preview-link {
  color: var(--text-2);
  margin-left: 10px;
  font-size: 12px;
  text-decoration: none;
  border-bottom: 1px dashed var(--text-3);
}

.preview-link:hover {
  color: var(--primary);
  border-color: rgba(0, 255, 157, 0.5);
}

.preview-open {
  margin-left: auto;
  color: var(--text-3);
  font-size: 11px;
  cursor: pointer;
  user-select: none;
}

.preview-open:hover {
  color: var(--primary);
}

.preview-frame {
  width: 100%;
  height: 400px;
  border: none;
  background: #fff;
}

.cursor-blink {
  animation: blink 1s steps(2, start) infinite;
}

@keyframes blink {
  to {
    visibility: hidden;
  }
}

/* 部署按钮 loading 态:小圆圈转圈,不显示「部署中…」文字 */
.btn-loading {
  opacity: 0.75;
  cursor: wait;
}

.spinner {
  display: inline-block;
  width: 12px;
  height: 12px;
  margin-right: 6px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  vertical-align: -2px;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 900px) {
  .project-grid {
    grid-template-columns: 1fr;
  }
  .tree-pane {
    display: none;
  }
}
</style>
