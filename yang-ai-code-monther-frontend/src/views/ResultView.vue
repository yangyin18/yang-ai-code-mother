<script setup lang="ts">
/**
 * 结果页:分栏工作台(v0.dev 风格)
 *
 *   ┌──────────────────────────────────────────────┐
 *   │ ← 新建应用   应用名   [重新生成][下载代码][部署上线]│ ← 工具条
 *   ├──────────────────────┬───────────────────────┤
 *   │  实时预览 (iframe)    │  index.html [复制]      │
 *   └──────────────────────┴───────────────────────┘
 *
 * 连接真实后端后:
 *  - 左侧预览区用 iframe 直接运行 AI 生成的代码(srcdoc,沙箱隔离)
 *  - 工具条「部署上线」调用 POST /app/deploy 发布到 nginx,
 *    成功后给出 http://localhost/apps/{deployKey}/ 访问地址
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { deployApp } from '@/api/generation'
import { useGenerationStore } from '@/stores/generation'
import { highlight } from '@/utils/highlight'
import { downloadCodeFiles } from '@/utils/download'
import type { CodeFile } from '@/types'

const router = useRouter()
const store = useGenerationStore()

/** 生成的应用 */
const app = computed(() => store.app)

/** 当前选中的代码文件名 */
const activeFileKey = ref('')

/** 部署中标记 */
const deploying = ref(false)

/** 代码文件列表(由 html/css/js 代码拼出,文件名为空则按扩展名回退) */
const files = computed<CodeFile[]>(() => {
  if (!app.value) return []
  const names = app.value.fileNames ?? []
  const contents: Record<string, string> = {
    'index.html': app.value.htmlCode,
    'style.css': app.value.cssCode,
    'script.js': app.value.jsCode,
  }
  const list: CodeFile[] = []
  for (const n of names) {
    const content = contents[n]
    if (content) list.push({ name: n, content })
  }
  if (list.length > 0) return list
  if (app.value.htmlCode) list.push({ name: 'index.html', content: app.value.htmlCode })
  if (app.value.cssCode) list.push({ name: 'style.css', content: app.value.cssCode })
  if (app.value.jsCode) list.push({ name: 'script.js', content: app.value.jsCode })
  return list
})

/** 当前展示的代码文件 */
const activeFile = computed(
  () => files.value.find((f) => f.name === activeFileKey.value) ?? null,
)

/** 高亮后的 HTML(用 v-html 渲染) */
const highlightedCode = computed(() =>
  activeFile.value ? highlight(activeFile.value.content, activeFile.value.name) : '',
)

/**
 * 拼出 iframe 预览文档:html 模式已含完整页面;
 * multi_file 模式把 css/js 注入到对应位置,保证能独立渲染。
 */
const previewDoc = computed(() => {
  if (!app.value) return ''
  let doc = app.value.htmlCode
  if (app.value.cssCode && !/<style/i.test(doc)) {
    doc = doc.replace(/<head([^>]*)>/i, (m, attrs) => {
      const css = app.value?.cssCode ?? ''
      return `<head${attrs}>\n<style>${css}<\/style>`
    })
  }
  if (app.value.jsCode && !/<script/i.test(doc)) {
    const js = app.value?.jsCode ?? ''
    doc = doc.replace(/<\/body>/i, `<script>${js}<\/script>\n<\/body>`)
  }
  return doc
})

/** 复制当前代码 */
function handleCopy() {
  if (!activeFile.value) return
  navigator.clipboard.writeText(activeFile.value.content)
  message.success('代码已复制到剪贴板')
}

/** 下载全部代码(单文件按原扩展名下载,多文件拼接为 txt) */
function handleDownload() {
  if (!app.value) return
  downloadCodeFiles(app.value.name, files.value)
}

/** 部署上线:未部署则调后端发布到 nginx,已部署则直接打开站点 */
async function handleDeploy() {
  if (!app.value) return
  if (app.value.deployUrl) {
    window.open(app.value.deployUrl, '_blank')
    return
  }
  deploying.value = true
  try {
    const url = await deployApp(app.value.appId)
    app.value.deployUrl = url
    message.success('部署成功,已发布到 nginx')
    window.open(url, '_blank')
  } catch {
    // 错误已由 request 拦截器统一提示
  } finally {
    deploying.value = false
  }
}

/** 重新生成:清空状态回首页 */
function handleRegenerate() {
  store.reset()
  router.push('/')
}

/** 回到首页 */
function goHome() {
  store.reset()
  router.push('/')
}

onMounted(() => {
  if (!app.value) {
    router.replace('/')
    return
  }
  activeFileKey.value = files.value[0]?.name ?? ''
})
</script>

<template>
  <div v-if="app" class="workspace">
    <!-- 工具条 -->
    <div class="toolbar">
      <a-button size="small" type="text" @click="goHome">← 新建应用</a-button>

      <div class="toolbar-title">
        <span class="tb-icon">⚡</span>
        <span class="tb-name">{{ app.name }}</span>
        <span class="tb-desc">{{ app.description || 'AI 生成的网页应用' }}</span>
      </div>

      <div class="toolbar-actions">
        <a-button size="small" @click="handleRegenerate">重新生成</a-button>
        <a-button size="small" @click="handleDownload">⬇ 下载代码</a-button>
        <a-button
          v-if="app.deployUrl"
          size="small"
          type="primary"
          @click="handleDeploy"
        >
          🔗 打开网站
        </a-button>
        <a-button v-else size="small" type="primary" :loading="deploying" @click="handleDeploy">
          🚀 部署上线
        </a-button>
      </div>
    </div>

    <!-- 部署地址条 -->
    <div v-if="app.deployUrl" class="deploy-bar">
      <span class="deploy-dot" />已部署到 nginx:
      <a
        class="deploy-link"
        :href="app.deployUrl"
        target="_blank"
        rel="noopener"
      >{{ app.deployUrl }}</a>
    </div>

    <!-- 主体两栏 -->
    <div class="workspace-body">
      <!-- 左:实时预览(iframe 运行真实生成的代码) -->
      <section class="pane preview-pane">
        <div class="pane-header">
          <span>实时预览</span>
          <span class="live-badge"><i class="live-dot" />LIVE</span>
        </div>
        <div class="pane-content preview-content">
          <iframe
            class="preview-frame"
            :srcdoc="previewDoc"
            sandbox="allow-scripts allow-modals"
            title="应用预览"
          />
        </div>
      </section>

      <!-- 右:代码 -->
      <section class="pane code-pane">
        <div class="pane-header">
          <div class="code-tabs">
            <button
              v-for="f in files"
              :key="f.name"
              class="code-tab"
              :class="{ active: activeFileKey === f.name }"
              @click="activeFileKey = f.name"
            >
              {{ f.name }}
            </button>
          </div>
          <a-button size="small" type="text" @click="handleCopy">📋 复制</a-button>
        </div>
        <div class="pane-content code-content">
          <pre class="code-block" v-html="highlightedCode" />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ---------- 工作台外壳 ---------- */
.workspace {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 20px;
  overflow: hidden;
  box-shadow: var(--shadow);
  animation: fade-up 0.5s ease both;
}

/* 工具条 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  background: rgba(21, 27, 38, 0.02);
  flex-wrap: wrap;
}

.toolbar-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}

.tb-icon {
  font-size: 18px;
}

.tb-name {
  font-weight: 700;
  font-size: 15px;
}

.tb-desc {
  color: var(--text-3);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
}

/* 部署地址条 */
.deploy-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 16px;
  border-bottom: 1px solid rgba(52, 211, 153, 0.4);
  background: rgba(52, 211, 153, 0.14);
  font-size: 13px;
  color: #059669;
  flex-wrap: wrap;
}

.deploy-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--success);
  animation: blink 1.4s infinite;
}

.deploy-link {
  color: #047857;
  text-decoration: none;
  word-break: break-all;
  font-weight: 600;
}

.deploy-link:hover {
  text-decoration: underline;
  color: #065f46;
}

/* 主体两栏 */
.workspace-body {
  display: grid;
  grid-template-columns: minmax(320px, 42%) 1fr;
  height: 660px;
}

@media (max-width: 860px) {
  .workspace-body {
    grid-template-columns: 1fr;
    height: auto;
  }
}

.pane {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.preview-pane {
  border-right: 1px solid var(--border);
}

@media (max-width: 860px) {
  .preview-pane {
    border-right: none;
    border-bottom: 1px solid var(--border);
  }
}

.pane-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
  color: var(--text-2);
  background: rgba(21, 27, 38, 0.02);
  flex-shrink: 0;
}

.pane-content {
  flex: 1;
  overflow: auto;
}

/* LIVE 徽标 */
.live-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--success);
  border: 1px solid rgba(52, 211, 153, 0.4);
  background: rgba(52, 211, 153, 0.08);
  border-radius: 6px;
  padding: 1px 7px;
}

.live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--success);
  animation: blink 1.4s infinite;
}

/* ---------- 预览区:真实运行生成的页面 ---------- */
.preview-content {
  padding: 0;
  background: #ffffff;
}

.preview-frame {
  width: 100%;
  height: 100%;
  border: none;
  background: #fff;
}

/* ---------- 代码区(深色编辑器:浅色页面中刻意保留) ---------- */
.code-pane {
  background: #0d1220;
}

/* 代码面板内的标题条/文件 tab 保持深色系,不随浅色主题 */
.code-pane .pane-header {
  background: #0d1220;
  border-bottom-color: rgba(255, 255, 255, 0.08);
  color: #9ca3af;
}

.code-content {
  padding: 0;
}

.code-tabs {
  display: flex;
  gap: 4px;
  overflow-x: auto;
}

.code-tab {
  padding: 5px 14px;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  color: #9ca3af;
  background: transparent;
  cursor: pointer;
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  white-space: nowrap;
}

.code-tab:hover {
  color: #fff;
}

.code-tab.active {
  background: rgba(0, 255, 157, 0.15);
  color: var(--primary);
}

/* 代码面板标题条里的 antd 文字按钮(浅色主题下默认文字是深色,深色面板上不可读) */
.code-pane .pane-header :deep(.ant-btn) {
  color: #9ca3af;
}

.code-pane .pane-header :deep(.ant-btn:hover) {
  color: #fff;
}

.code-block {
  margin: 0;
  padding: 18px 20px;
  color: #d4d4d4;
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre;
}

/* 自定义 hljs 深色主题 token */
.code-block :deep(.hljs-comment),
.code-block :deep(.hljs-quote) {
  color: #6b7280;
  font-style: italic;
}

.code-block :deep(.hljs-string) {
  color: #86efac;
}

.code-block :deep(.hljs-keyword),
.code-block :deep(.hljs-selector-tag) {
  color: #c4b5fd;
}

.code-block :deep(.hljs-name),
.code-block :deep(.hljs-tag) {
  color: #93c5fd;
}

.code-block :deep(.hljs-attr),
.code-block :deep(.hljs-attribute) {
  color: #fbbf24;
}

.code-block :deep(.hljs-title),
.code-block :deep(.hljs-section) {
  color: #7dd3fc;
}

.code-block :deep(.hljs-number),
.code-block :deep(.hljs-literal) {
  color: #f9a8d4;
}

.code-block :deep(.hljs-built_in),
.code-block :deep(.hljs-variable) {
  color: #5eead4;
}

.code-block :deep(.hljs-template-variable),
.code-block :deep(.hljs-params) {
  color: #e5e7eb;
}

/* ---------- 动画 ---------- */
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

@keyframes blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.3;
  }
}
</style>
