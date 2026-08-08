<script setup lang="ts">
/**
 * 结果页:分栏工作台(v0.dev 风格)
 *
 *   ┌──────────────────────────────────────────────┐
 *   │ ← 新建应用   应用名   [重新生成] [下载代码]      │ ← 工具条
 *   ├──────────────────────┬───────────────────────┤
 *   │  实时预览 (模拟界面)   │  index.vue [复制]       │
 *   └──────────────────────┴───────────────────────┘
 *
 * 学习点:
 *  1. highlight.js 按需注册语言做代码高亮(utils/highlight.ts)
 *  2. 预览区用「浅色应用卡片」对比深色工作台,制造真实应用被预览的感觉
 *  3. v-html 渲染高亮结果(highlight.js 输出已转义,安全)
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useGenerationStore } from '@/stores/generation'
import { highlight } from '@/utils/highlight'

const router = useRouter()
const store = useGenerationStore()

/** 生成的应用 */
const app = computed(() => store.app)

/** 当前选中的代码文件名 */
const activeFileKey = ref('')

/** 当前展示的代码文件 */
const activeFile = computed(
  () => app.value?.files.find((f) => f.name === activeFileKey.value) ?? null,
)

/** 高亮后的 HTML(用 v-html 渲染) */
const highlightedCode = computed(() =>
  activeFile.value ? highlight(activeFile.value.content, activeFile.value.name) : '',
)

/** 预览区交互次数(演示) */
const previewClicks = ref(0)

/** 预览按钮点击 */
function handleAction(label: string) {
  previewClicks.value += 1
  message.success(`你点击了「${label}」,模拟预览共交互 ${previewClicks.value} 次`)
}

/** 复制当前代码 */
function handleCopy() {
  if (!activeFile.value) return
  navigator.clipboard.writeText(activeFile.value.content)
  message.success('代码已复制到剪贴板')
}

/** 下载全部代码(拼接为一个文本文件) */
function handleDownload() {
  if (!app.value) return
  const all = app.value.files.map((f) => `// ===== ${f.name} =====\n${f.content}`).join('\n\n')
  const blob = new Blob([all], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${app.value.name}-生成的代码.txt`
  a.click()
  URL.revokeObjectURL(url)
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
  activeFileKey.value = app.value.files[0]?.name ?? ''
})
</script>

<template>
  <div v-if="app" class="workspace">
    <!-- 工具条 -->
    <div class="toolbar">
      <a-button size="small" type="text" @click="goHome">← 新建应用</a-button>

      <div class="toolbar-title">
        <span class="tb-icon">{{ app.icon }}</span>
        <span class="tb-name">{{ app.name }}</span>
        <span class="tb-desc">{{ app.description }}</span>
      </div>

      <div class="toolbar-actions">
        <a-button size="small" @click="handleRegenerate">重新生成</a-button>
        <a-button size="small" type="primary" @click="handleDownload">⬇ 下载代码</a-button>
      </div>
    </div>

    <!-- 主体两栏 -->
    <div class="workspace-body">
      <!-- 左:实时预览 -->
      <section class="pane preview-pane">
        <div class="pane-header">
          <span>实时预览</span>
          <span class="live-badge"><i class="live-dot" />LIVE</span>
        </div>
        <div class="pane-content preview-content">
          <!-- 模拟生成的应用界面(浅色卡片,对比深色工作台) -->
          <div class="phone">
            <div class="phone-bar" />
            <div class="phone-header">{{ app.preview.title }}</div>
            <div class="phone-stats">
              <div v-for="s in app.preview.stats" :key="s.label" class="phone-stat">
                <div class="stat-label">{{ s.label }}</div>
                <div class="stat-value">{{ s.value }}</div>
              </div>
            </div>
            <div class="phone-actions">
              <button
                v-for="a in app.preview.actions"
                :key="a.key"
                class="phone-btn"
                @click="handleAction(a.label)"
              >
                {{ a.label }}
              </button>
            </div>
            <div class="phone-records">
              <div v-for="(r, i) in app.preview.records" :key="i" class="phone-record">
                <span class="record-date">{{ r.date }}</span>
                <span class="record-content">{{ r.content }}</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- 右:代码 -->
      <section class="pane code-pane">
        <div class="pane-header">
          <div class="code-tabs">
            <button
              v-for="f in app.files"
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
  background: rgba(255, 255, 255, 0.02);
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

/* 主体两栏 */
.workspace-body {
  display: grid;
  grid-template-columns: minmax(320px, 38%) 1fr;
  height: 640px;
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
  background: rgba(255, 255, 255, 0.02);
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

/* ---------- 预览区:模拟应用界面 ---------- */
.preview-content {
  padding: 28px 20px;
  background-image: radial-gradient(rgba(255, 255, 255, 0.04) 1px, transparent 1px);
  background-size: 22px 22px;
  display: flex;
  align-items: flex-start;
  justify-content: center;
}

.phone {
  width: 100%;
  max-width: 300px;
  background: #ffffff;
  color: #1f2937;
  border-radius: 18px;
  overflow: hidden;
  box-shadow: 0 18px 50px rgba(0, 0, 0, 0.55);
  border: 1px solid rgba(255, 255, 255, 0.12);
}

.phone-bar {
  height: 26px;
  background: var(--gradient);
}

.phone-header {
  padding: 14px 16px;
  background: #f9fafb;
  border-bottom: 1px solid #e5e7eb;
  text-align: center;
  font-weight: 700;
  color: #111827;
  font-size: 15px;
}

.phone-stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  padding: 14px;
}

.phone-stat {
  background: #f3f4f6;
  border-radius: 10px;
  padding: 10px;
  text-align: center;
}

.stat-label {
  font-size: 11px;
  color: #6b7280;
}

.stat-value {
  font-size: 16px;
  font-weight: 800;
  background: var(--gradient);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  margin-top: 2px;
}

.phone-actions {
  padding: 0 14px 12px;
}

.phone-btn {
  width: 100%;
  padding: 11px;
  border: none;
  border-radius: 10px;
  background: var(--gradient);
  color: #fff;
  font-weight: 700;
  font-size: 14px;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.phone-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 20px rgba(99, 102, 241, 0.35);
}

.phone-records {
  padding: 0 14px 16px;
}

.phone-record {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px dashed #e5e7eb;
  font-size: 12px;
}

.phone-record:last-child {
  border-bottom: none;
}

.record-date {
  color: #9ca3af;
  flex-shrink: 0;
}

/* ---------- 代码区 ---------- */
.code-pane {
  background: #0d1220;
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
  color: var(--text-2);
  background: transparent;
  cursor: pointer;
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  white-space: nowrap;
}

.code-tab:hover {
  color: #fff;
}

.code-tab.active {
  background: rgba(99, 102, 241, 0.18);
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
