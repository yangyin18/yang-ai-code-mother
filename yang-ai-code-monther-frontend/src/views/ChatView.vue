<script setup lang="ts">
/**
 * 应用对话页面(/chat/:appId)
 *
 * 行为:
 *  1. 进入即按游标加载该应用最新 10 条对话,按 createTime 升序展示(区分 AI/用户气泡);
 *  2. 消息总数 > 10 时,消息上方显示「加载更多」,用游标(当前最旧消息 id)加载更早历史并前插;
 *  3. 仅当是「自己的应用」且「无任何历史」时,自动把 initPrompt 作为第一条消息发出,并流式接收 AI 回复;
 *  4. 应用有 ≥2 条聊天记录且已部署时,顶部显示「查看网站」入口;
 *  5. 发送消息走 POST /chat/send(SSE),AI 回复边收边显示,结束后固定气泡。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { editCodeStyle, editCodeText, getAppCode, getAppDetail, deployAppStream, downloadAppZip } from '@/api/generation'
import { loadChatHistory, sendChatMessage, type AppUpdatedPayload, type ChatMessage } from '@/api/chat'
import { useUserStore } from '@/stores/user'
import { extractHtmlCode } from '@/utils/sseDisplay'
import { highlight } from '@/utils/highlight'
import {
  buildElementPrompt,
  formatElementLabel,
  injectEditorScript,
  notifyApplyStyle,
  notifyApplyText,
  notifyClearSelection,
  notifyEditMode,
  notifyRestoreStyle,
  parseElementFromEvent,
  type SelectedElement,
} from '@/utils/visualEdit'
import type { CodeFile } from '@/types'

/** 应用详情(后端 AppVO 字段子集) */
interface AppInfo {
  id: string
  appName?: string
  initPrompt?: string
  deployUrl?: string
  userId?: string
  /** 生成类型:vue 深度开发应用不走对话流,重定向到项目页 */
  codeGenType?: string
}

/** 本地消息:流式中的 AI 气泡没有 id,用 localId 兜底,complete 后补 id */
interface LocalMessage extends ChatMessage {
  localId: string
  streaming?: boolean
}

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const appId = computed(() => String(route.params.appId ?? ''))

const app = ref<AppInfo | null>(null)

/** 是否为深度开发(Vue 项目)应用:预览/代码展示/下载走 Vue 分支(无内联 html/css/js) */
const isVue = computed(() => app.value?.codeGenType === 'vue')

const messages = ref<LocalMessage[]>([])
const historyLoading = ref(true)
const loadMoreLoading = ref(false)
const denied = ref(false)
const sending = ref(false)
/** 可视化编辑「直接改文字/样式」保存中(调后端替换接口,不占用对话 sending 状态) */
const visualSaving = ref(false)
const inputText = ref('')
const bodyRef = ref<HTMLElement | null>(null)

/** 对话即改代码后,用最新代码拼的 srcdoc 预览(优先于已部署 iframe) */
const previewDoc = ref('')
/** 正在自动应用修改(文字回复完成后,代码重新生成 + 部署中) */
const appUpdating = ref(false)
/** 手动部署中 */
const deploying = ref(false)
/** 部署实时进度字符串(SSE progress 事件逐条刷新,阶段说明 + npm 输出行) */
const deployMsg = ref('')
/** 已部署 iframe 的缓存破除版本号(appUpdated 携带新部署地址时 +1 强制刷新) */
const previewVersion = ref(0)

/** 可视化编辑:是否开启编辑模式(仅 srcdoc 实时预览可用) */
const editMode = ref(false)
/** 可视化编辑:当前选中的网页元素(iframe 回传,发送时并入提示词) */
const selectedElement = ref<SelectedElement | null>(null)
/** 预览 iframe 元素引用(向 iframe 内注入的编辑脚本发送开启/关闭消息) */
const previewFrame = ref<HTMLIFrameElement | null>(null)
/** 可视化编辑:选中元素的完整可见文本(有文字才展示「直接改文字」编辑框,无则返回空串) */
const selText = computed(() => {
  const s = selectedElement.value
  return s && (s.fullText || s.text) ? (s.fullText || s.text) : ''
})
/** 可视化编辑:「直接改文字」的草稿(选中元素变化时预填当前文本,供用户直接修改) */
const textDraft = ref('')
/** 可视化编辑:当前选中元素的元素身份标识(选中变化/回显判断用,tag+path 在同一元素上保持不变) */
let selKey = ''
/** 可视化编辑:当前选中元素的原始内联样式快照(选中时记录,保存 diff / 判断是否真改动用) */
let origStyle: Record<string, string> = {}
/** 可视化编辑:颜色草稿(选中元素变化时预填当前内联 color,空表示没有/清除) */
const colorDraft = ref('')
/** 可视化编辑:内边距草稿(px 值字符串,如 '8px'、'auto'、'';空表示清除) */
const paddingDraft = ref('')
/** 可视化编辑:外边距草稿(px 值字符串,如 '8px'、'auto'、'';空表示清除) */
const marginDraft = ref('')
watch(selectedElement, (sel) => {
  const key = sel ? `${sel.tag}|${sel.path}` : ''
  // iframe 应用文字/样式后会回显 SELECT 事件(同一元素),此时不重置草稿,避免打断用户输入
  if (key === selKey) return
  selKey = key
  textDraft.value = sel && (sel.fullText || sel.text) ? (sel.fullText || sel.text) : ''
  const inline = parseInlineStyle(sel?.inlineStyle ?? '')
  origStyle = inline
  colorDraft.value = inline.color ?? ''
  paddingDraft.value = inline.padding ?? ''
  marginDraft.value = inline.margin ?? ''
})

/** 右侧面板标签:实时预览 / 代码 */
const activeTab = ref<'preview' | 'code'>('preview')
/** 代码流式原始累积(后端 codeChunk 增量,原样拼接,不 trim) */
const codeStreamRaw = ref('')
/** 从原始流中实时提取的干净代码(展示 + 节流刷新预览) */
const displayCode = computed(() => extractHtmlCode(codeStreamRaw.value))
/** 流式期间节流刷新预览的定时器(400ms,同生成页,避免每 token 重建 iframe) */
let previewTimer: ReturnType<typeof setInterval> | null = null
/** Vue 深度开发:项目已生成完成(代码 tab 展示完成态,不放出原始代码文件) */
const vueDone = ref(false)
/** Vue 深度开发:已写入文件的相对路径(file 事件逐个推送,展示真实 writeFile 工具调用) */
const vueFiles = ref<string[]>([])

/** 生成完成的代码文件列表(appUpdated payload 拼出,供「代码」tab 分文件展示) */
const activeFileKey = ref('')
const files = ref<CodeFile[]>([])
const activeFile = computed(() => files.value.find((f) => f.name === activeFileKey.value) ?? null)
/** 高亮后的当前文件代码(v-html 渲染) */
const highlightedCode = computed(() =>
  activeFile.value ? highlight(activeFile.value.content, activeFile.value.name) : '',
)

/** 流式代码区滚动容器(自动滚到底部,最新代码始终可见) */
const streamCodeRef = ref<HTMLElement | null>(null)

/** 每次代码增量后把流式代码区滚到底部,让用户始终看到最新生成的代码 */
watch(
  codeStreamRaw,
  () => {
    if (!streamCodeRef.value) return
    const el = streamCodeRef.value
    el.scrollTop = el.scrollHeight
  },
  { flush: 'post' },
)

/** 流式中切到「代码」tab 时,直接跳到最新代码位置 */
watch(activeTab, async (tab) => {
  if (tab === 'code' && codeStreamRaw.value) {
    await nextTick()
    if (streamCodeRef.value) streamCodeRef.value.scrollTop = streamCodeRef.value.scrollHeight
  }
})

/** 停止预览节流定时器(幂等) */
function stopPreviewTimer() {
  if (previewTimer) {
    clearInterval(previewTimer)
    previewTimer = null
  }
}

/** 自动发送 initPrompt 的防重入标记 */
let autoInitSent = false
/** 当前发送请求的取消控制器(离开页面时中断流) */
let sendAbort: AbortController | null = null

const isOwnApp = computed(() => !!app.value && app.value.userId === userStore.userInfo?.id)
const isAdmin = computed(() => userStore.userInfo?.userRole === 'admin')
const canChat = computed(() => isOwnApp.value || isAdmin.value)
const hasMore = ref(false)

/** 应用有 ≥2 条聊天记录且已部署时,展示「查看网站」 */
const showWebsite = computed(() => !!app.value?.deployUrl && messages.value.length >= 2)

/** 预览窗展示形态:最新代码 srcdoc > 已部署 iframe > 占位。
 *  Vue 深度开发没有可独立运行的内联 html/css/js(htmlCode 只是工程 index.html,
 *  引用 src 模块在 srcdoc 里跑不起来),不做 srcdoc 预览 —— 已部署则用已部署 iframe,
 *  未部署则占位(深度开发预览就是部署后的站点)。 */
const previewMode = computed<'srcdoc' | 'iframe' | 'empty'>(() => {
  if (!isVue.value && previewDoc.value) return 'srcdoc'
  if (app.value?.deployUrl) return 'iframe'
  return 'empty'
})

/** 已部署 iframe 地址(带缓存破除参数,重新部署后强制刷新) */
const previewSrc = computed(() => {
  const url = app.value?.deployUrl
  return url ? `${url}?t=${previewVersion.value}` : ''
})

/** 把 html/css/js 拼成可独立渲染的 srcdoc(复用生成页的注入逻辑)
 * 注意:闭合标签必须写 `<\/script>` / `<\/body>` 转义,否则 SFC 解析器会误判脚本块提前结束。
 * 末尾注入可视化编辑脚本(injectEditorScript):iframe 内脚本挂载即监听,默认休眠,
 * 收到父页面 ENABLE 消息才激活悬浮高亮与点击选中,选中元素经 postMessage 回传。 */
function buildPreviewDoc(html: string, css: string, js: string) {
  let doc = html
  if (css && !/<style/i.test(doc)) {
    doc = doc.replace(/<head([^>]*)>/i, (match: string, attrs: string) => `<head${attrs}>\n<style>${css}<\/style>`)
  }
  if (js && !/<script/i.test(doc)) {
    doc = doc.replace(/<\/body>/i, (match: string) => `<script>${js}<\/script>\n<\/body>`)
  }
  return injectEditorScript(doc)
}

/** 是否正在等待 AI 首个 token(用于展示"思考中..."提示) */
const waitingAi = ref(false)

/** 已登录检查:未登录先跳登录页 */
if (!userStore.isLoggedIn) {
  router.replace('/login')
}

/** 时间格式化(去掉秒) */
function fmtTime(t?: string): string {
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 进入页面:加载应用详情 + 最新一页对话,并按条件自动发送 initPrompt */
async function init() {
  historyLoading.value = true
  denied.value = false
  try {
    app.value = await getAppDetail(appId.value)
  } catch {
    app.value = null
    message.error('应用不存在')
    historyLoading.value = false
    return
  }

  // 未登录(刷新后登录态恢复完成前)不继续
  if (!userStore.isLoggedIn) {
    historyLoading.value = false
    return
  }

  // 应用已有生成产物:把上次的代码/预览加载进右栏,避免进入页面重新生成
  const hasExistingCode = await loadExistingCode()

  try {
    const page = await loadChatHistory(appId.value)
    // 后端按新→旧返回,翻转成旧→新升序展示
    messages.value = page.records.map((r, i) => ({ ...r, localId: `h${i}` })).reverse()
    hasMore.value = page.hasMore
  } catch {
    denied.value = true
    hasMore.value = false
    historyLoading.value = false
    return
  }
  historyLoading.value = false

  // 仅自己的应用、无任何历史、且还没有生成过代码时,才自动把 initPrompt 作为第一条消息发出
  if (!autoInitSent && isOwnApp.value && messages.value.length === 0 && !hasExistingCode) {
    autoInitSent = true
    const initPrompt = app.value?.initPrompt
    if (initPrompt) {
      await sendMessage(initPrompt, true)
    }
  }
}

/**
 * 应用已生成过代码时,把之前的成果加载到右栏(预览 + 代码分文件)。
 * 返回是否已有代码:已有 → 不自动重发 initPrompt,避免重新生成。
 */
async function loadExistingCode(): Promise<boolean> {
  try {
    const res = await getAppCode(appId.value)
    const list: CodeFile[] = (res.files ?? []).map((f) => ({ name: f.path, content: f.content }))
    if (list.length === 0) {
      // 兜底:按 index.html / style.css / script.js 组装
      if (res.htmlCode) list.push({ name: 'index.html', content: res.htmlCode })
      if (res.cssCode) list.push({ name: 'style.css', content: res.cssCode })
      if (res.jsCode) list.push({ name: 'script.js', content: res.jsCode })
    }
    if (list.length === 0) return false
    files.value = list
    // Vue 工程:已有产物即视为「已生成完成」,代码 tab 展示完成态而非原始代码
    if (isVue.value) vueDone.value = true
    if (list[0] && !activeFileKey.value) activeFileKey.value = list[0].name
    // 用已生成代码拼 srcdoc,未部署的应用也能直接实时预览
    // (Vue 工程无独立可运行的内联代码,index.html 引用 src 模块在 srcdoc 跑不起来,跳过)
    if (!isVue.value && res.htmlCode) {
      previewDoc.value = buildPreviewDoc(res.htmlCode, res.cssCode ?? '', res.jsCode ?? '')
    }
    return true
  } catch {
    // 读取失败不阻塞页面,按无产物处理
    return false
  }
}

// ==================== 可视化编辑(逻辑抽在 utils/visualEdit.ts) ====================

/**
 * 编辑模式开关。开启后通知 iframe 内脚本激活悬浮高亮与点击选中,
 * 关闭时清空选中并让 iframe 退出编辑。仅 srcdoc 实时预览可用
 * (已部署 iframe 是跨域 nginx 站点,父页面无法注入脚本)。
 */
function toggleEditMode() {
  if (previewMode.value !== 'srcdoc') {
    message.info('可视化编辑仅支持实时预览(最新代码)')
    return
  }
  editMode.value = !editMode.value
  if (!editMode.value) {
    selectedElement.value = null
  }
  notifyEditMode(previewFrame.value, editMode.value)
}

/** 移除选中元素:清空本地状态并通知 iframe 清除边框 */
function clearSelection() {
  selectedElement.value = null
  notifyClearSelection(previewFrame.value)
}

/** 把内联 style 字符串解析成属性 map(键小写) */
function parseInlineStyle(style: string): Record<string, string> {
  const out: Record<string, string> = {}
  if (!style) return out
  for (const part of style.split(';')) {
    const ci = part.indexOf(':')
    if (ci <= 0) continue
    const k = part.slice(0, ci).trim()
    const v = part.slice(ci + 1).trim()
    if (k && v) out[k.toLowerCase()] = v
  }
  return out
}

/** 把样式值转成 a-input-number 能显示的数值:'8px' → 8,'auto'/'8%' → null(显示空) */
function numOf(v: string): number | null {
  if (!v) return null
  const n = parseFloat(v)
  return Number.isFinite(n) ? n : null
}

/** 把样式值规范化成可写入代码的形式:纯数字补 px,其余原样('auto'、'10%' 等) */
function toPx(v: string): string {
  const t = (v ?? '').trim()
  return /^\d+(\.\d+)?$/.test(t) ? t + 'px' : t
}

/** 当前样式草稿 → 完整样式表(空串表示清除该属性) */
function buildStyleMap(): Record<string, string> {
  return {
    color: colorDraft.value || '',
    padding: toPx(paddingDraft.value),
    margin: toPx(marginDraft.value),
  }
}

/** 把当前样式草稿所见即所得地应用到 iframe 选中元素(实时预览,不调 AI) */
function applyStyleLive() {
  notifyApplyStyle(previewFrame.value, buildStyleMap())
}

/** 颜色选择器 @input:更新草稿并实时应用到预览 */
function onColorInput(e: Event) {
  colorDraft.value = (e.target as HTMLInputElement).value
  applyStyleLive()
}

/** 内边距数字输入 @change:更新草稿并实时应用到预览 */
function onPaddingChange(v: number | string | null) {
  paddingDraft.value = v == null ? '' : String(v)
  applyStyleLive()
}

/** 外边距数字输入 @change:更新草稿并实时应用到预览 */
function onMarginChange(v: number | string | null) {
  marginDraft.value = v == null ? '' : String(v)
  applyStyleLive()
}

/**
 * 保存可视化编辑(文字 + 颜色/内边距/外边距):不调 AI,先把修改所见即所得地应用到
 * iframe 预览,再调后端把代码文件里的对应修改写回(仅小幅度改动,其余代码原样保留),
 * 然后刷新右栏代码/预览;已部署的应用自动重新部署,让线上站点同步。
 * 失败时把 iframe 预览回滚为选中时的原文/原样式。
 */
async function saveVisualEdit() {
  const sel = selectedElement.value
  if (!sel) return

  // 文字:元素无文字时跳过文字部分;有文字则必须非空且真的变化了才算修改
  const newText = (textDraft.value ?? '').trim()
  const oldText = (sel.fullText || sel.text || '').trim()
  const textChanged = !!oldText && newText !== oldText
  if (selText.value && !newText) {
    message.warning('文字不能为空')
    return
  }

  // 样式:与选中时的原始内联样式对比,只发送真正变化/清除的属性(避免空保存刷新 updateTime)
  const intended = buildStyleMap()
  const styleChanges: Record<string, string> = {}
  for (const k of ['color', 'padding', 'margin'] as const) {
    if (intended[k] !== (origStyle[k] ?? '')) styleChanges[k] = intended[k]
  }
  const styleChanged = Object.keys(styleChanges).length > 0

  if (!textChanged && !styleChanged) {
    message.info('没有需要保存的修改')
    return
  }

  visualSaving.value = true
  try {
    // 1. 同步到代码文件(先文字后样式;样式定位锚点用写盘后的最新文本)
    if (textChanged) await editCodeText(appId.value, oldText, newText)
    if (styleChanged) {
      await editCodeStyle({
        appId: appId.value,
        tag: sel.tag,
        id: sel.id,
        className: sel.className,
        text: textChanged ? newText : oldText,
        style: styleChanges,
      })
    }
    message.success('✓ 修改已保存,代码已同步')
    // 2. 用后端返回的最新代码刷新右栏预览 + 代码分文件
    await loadExistingCode()
    // 3. 已部署应用:自动重新部署,让线上站点同步(复用原 deployKey,访问地址不变)
    if (app.value?.deployUrl) {
      deployMsg.value = ''
      deployAppStream(appId.value, { onProgress: (m) => (deployMsg.value = m) })
        .then((url) => {
          app.value!.deployUrl = url
          previewVersion.value += 1
          message.success('✓ 线上站点已同步更新')
        })
        .catch((e) => message.warning(`线上站点更新失败:${(e as Error).message || '未知原因'}`))
    }
    clearSelection()
  } catch (e) {
    // 4. 失败:回滚 iframe 预览为原文/原样式,并提示用户
    if (textChanged) notifyApplyText(previewFrame.value, oldText)
    if (styleChanged) notifyRestoreStyle(previewFrame.value)
    message.error((e as Error).message || '保存修改失败')
  } finally {
    visualSaving.value = false
  }
}

/**
 * 预览 iframe 加载/重建后回调:脚本挂载即休眠,若编辑模式仍开启则重新激活。
 * srcdoc 在流式期间每 400ms 重建一次,iframe 重建后脚本需重新收到 ENABLE 才工作。
 */
function onPreviewFrameLoad() {
  if (editMode.value) notifyEditMode(previewFrame.value, true)
}

/** 接收 iframe 内编辑脚本回传的选中元素消息(点击选中 / 取消) */
function onVisualEditMessage(e: MessageEvent) {
  const sel = parseElementFromEvent(e, previewFrame.value?.contentWindow ?? null)
  if (sel === undefined) return
  selectedElement.value = sel
}

// 挂载消息监听;离开页面时移除(与 onBeforeUnmount 成对)
window.addEventListener('message', onVisualEditMessage)

/**
 * 发送一条消息(自动触发时 silent 不弹空输入校验提示)。
 * 有选中的网页元素时,把元素定位信息并入提示词,让 AI 明确修改目标。
 */
async function sendMessage(text: string, silent = false) {
  const content = (text ?? '').trim()
  if (!content) {
    if (!silent) message.warning('先输入点什么再发送吧')
    return
  }
  if (sending.value) {
    if (!silent) message.info('AI 还在回复中,稍等一下')
    return
  }
  const prompt = buildElementPrompt(content, selectedElement.value)

  inputText.value = ''
  sending.value = true
  waitingAi.value = true

  // 1. 追加用户消息
  const userMsg: LocalMessage = {
    id: `u-${Date.now()}`,
    localId: `u-${Date.now()}`,
    appId: appId.value,
    userId: userStore.userInfo?.id ?? '',
    messageType: 'user',
    message: content,
    createTime: new Date().toISOString(),
  }
  messages.value.push(userMsg)

  // 2. 追加空的 AI 气泡,边收边填充
  const aiMsg: LocalMessage = {
    id: '',
    localId: `ai-${Date.now()}`,
    appId: appId.value,
    userId: userStore.userInfo?.id ?? '',
    messageType: 'ai',
    message: '',
    streaming: true,
  }
  messages.value.push(aiMsg)

  sendAbort = new AbortController()
  try {
    const done = await sendChatMessage(
      appId.value,
      prompt,
      (chunk) => {
        aiMsg.message += chunk
        waitingAi.value = false
      },
      {
        signal: sendAbort.signal,
        onStarted: () => {
          // 连接已建立,仍在等首个 token
        },
        onAppUpdating: () => {
          // 文字回复完成,开始自动改代码 + 重新部署:
          // 清空流式代码区,启动 400ms 节流实时预览(代码随 codeChunk 即时打出)
          appUpdating.value = true
          codeStreamRaw.value = ''
          // Vue 项目由 writeFile 工具逐个落盘,没有 html 代码流,不做 srcdoc 实时预览;
          // 重置真实文件日志,后续 file 事件逐个追加
          if (isVue.value) {
            vueFiles.value = []
            return
          }
          if (!previewTimer) {
            previewTimer = setInterval(() => {
              // 注入编辑脚本:流式重写期间保持 iframe 编辑能力
              previewDoc.value = injectEditorScript(displayCode.value)
            }, 400)
          }
        },
        onCodeChunk: (chunk) => {
          // Vue 的 codeChunk 是生成计划文本(无 html 代码流),忽略,不污染代码区
          if (isVue.value) return
          // 代码流式增量:代码区即时打出(原样拼接,不 trim)
          codeStreamRaw.value += chunk
        },
        onFile: (path) => {
          // Vue 深度开发:writeFile 工具每真实写入一个文件,追加到日志(去重,防止重复写入同一路径)
          if (path && vueFiles.value[vueFiles.value.length - 1] !== path) {
            vueFiles.value.push(path)
          }
        },
        onProgress: (msg) => {
          // 已部署应用自动重新部署:阶段 + npm 输出逐行反馈(字符串进度,非花哨界面)
          deployMsg.value = msg
        },
        onAppUpdated: (payload: AppUpdatedPayload) => {
          // 代码已重新生成(并重新部署):停止节流,用结构化结果刷新预览 + 代码文件
          stopPreviewTimer()
          // Vue:完整项目由工具落盘,payload 只有文件清单(无内联 html/css/js)。
          // 从后端拉最新文件刷新「代码」tab;已部署则刷新 iframe 预览
          if (isVue.value) {
            appUpdating.value = false
            vueDone.value = true
            if (payload.deployUrl && app.value) {
              app.value.deployUrl = payload.deployUrl
              previewVersion.value += 1
            }
            loadExistingCode()
            message.success('✓ Vue 项目已生成,点击「部署上线」即可预览')
            return
          }
          if (payload.htmlCode) {
            previewDoc.value = buildPreviewDoc(payload.htmlCode, payload.cssCode ?? '', payload.jsCode ?? '')
          }
          if (payload.deployUrl && app.value) {
            app.value.deployUrl = payload.deployUrl
            previewVersion.value += 1
          }
          // 由完整结果拼出代码文件列表,供「代码」tab 分文件展示
          const contents: Record<string, string> = {
            'index.html': payload.htmlCode ?? '',
            'style.css': payload.cssCode ?? '',
            'script.js': payload.jsCode ?? '',
          }
          const list: CodeFile[] = []
          for (const n of payload.fileNames ?? []) {
            if (contents[n]) list.push({ name: n, content: contents[n] })
          }
          if (list.length === 0 && payload.htmlCode) {
            list.push({ name: 'index.html', content: payload.htmlCode })
            if (payload.cssCode) list.push({ name: 'style.css', content: payload.cssCode })
            if (payload.jsCode) list.push({ name: 'script.js', content: payload.jsCode })
          }
          files.value = list
          if (list[0] && !activeFileKey.value) activeFileKey.value = list[0].name
          appUpdating.value = false
          message.success('✓ 应用已更新,预览已同步')
        },
      },
    )
    // complete:补上落库的 id / createTime,固定气泡
    aiMsg.id = done.id
    aiMsg.createTime = done.createTime
    aiMsg.streaming = false
    // 提交成功:清空选中元素、关闭编辑模式(其余业务逻辑不变)
    selectedElement.value = null
    if (editMode.value) {
      editMode.value = false
      notifyEditMode(previewFrame.value, false)
    }
  } catch (e) {
    aiMsg.streaming = false
    const errText = (e as Error).message
    if (errText === '已取消发送') {
      // 离开页面主动取消,不追加错误气泡
      messages.value = messages.value.filter((m) => m.localId !== aiMsg.localId)
    } else {
      aiMsg.messageType = 'error'
      aiMsg.message = errText || 'AI 回复失败,请稍后重试'
    }
  } finally {
    sending.value = false
    waitingAi.value = false
    // 兜底:代码生成失败(后端只发 complete 不发 appUpdated)时不卡住「正在更新预览」指示
    appUpdating.value = false
    stopPreviewTimer()
    sendAbort = null
  }
}

/** 加载更早的历史(游标 = 当前最旧消息 id),前插到列表 */
async function loadMore() {
  if (loadMoreLoading.value || !hasMore.value) return
  loadMoreLoading.value = true
  const cursorId = messages.value[0]?.id
  try {
    const page = await loadChatHistory(appId.value, cursorId)
    const older = page.records.map((r, i) => ({ ...r, localId: `o${Date.now()}-${i}` })).reverse()
    messages.value = [...older, ...messages.value]
    hasMore.value = page.hasMore
  } catch {
    message.error('加载更早的消息失败')
  } finally {
    loadMoreLoading.value = false
  }
}

/** 查看已部署网站 */
function openWebsite() {
  if (app.value?.deployUrl) {
    window.open(app.value.deployUrl, '_blank')
  }
}

function goBack() {
  router.push('/')
}

/** 下载全部代码(复用生成页逻辑):快速开发拼接为文本文件,Vue 项目直接下整个工程的 ZIP */
function handleDownload() {
  if (files.value.length === 0) return
  // Vue 是多文件工程,用后端打包的 ZIP 下载(内含完整目录结构)
  if (isVue.value) {
    downloadAppZip(appId.value).catch((e) => message.error((e as Error).message || '下载失败'))
    return
  }
  const all = files.value
    .map((f) => `// ===== ${f.name} =====\n${f.content}`)
    .join('\n\n')
  const blob = new Blob([all], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${app.value?.appName || '应用'}-代码.txt`
  a.click()
  URL.revokeObjectURL(url)
}

/** 部署上线:已部署则直接打开网站;未部署则流式发布到 nginx 后打开(全程显示真实进度字符串) */
async function handleDeploy() {
  if (!app.value) return
  if (app.value.deployUrl) {
    window.open(app.value.deployUrl, '_blank')
    return
  }
  deploying.value = true
  deployMsg.value = ''
  try {
    const url = await deployAppStream(appId.value, {
      onProgress: (msg) => {
        // SSE progress 事件:部署阶段 + npm 输出逐行刷新
        deployMsg.value = msg
      },
    })
    app.value.deployUrl = url
    previewVersion.value += 1
    deployMsg.value = ''
    message.success('部署成功,已发布到 nginx')
    window.open(url, '_blank')
  } catch (e) {
    // 流式错误(部署失败):保留最后一条进度,方便看清是在哪个阶段出的问题
    message.error((e as Error).message || '部署失败')
  } finally {
    deploying.value = false
  }
}

/** 新消息(含流式)到达时滚动到底部 */
watch(
  () => [messages.value.length, messages.value.at(-1)?.message],
  async () => {
    await nextTick()
    const el = bodyRef.value
    if (el) el.scrollTop = el.scrollHeight
  },
)

/** 进入页面时初始化 */
watch(
  () => appId.value,
  () => {
    if (userStore.isLoggedIn && appId.value) {
      messages.value = []
      hasMore.value = false
      autoInitSent = false
      vueDone.value = false
      vueFiles.value = []
      deployMsg.value = ''
      init()
    }
  },
  { immediate: true },
)

/** 登录态恢复完成后再初始化(刷新页面场景) */
watch(
  () => userStore.isLoggedIn,
  (v) => {
    if (v && appId.value && messages.value.length === 0 && !historyLoading.value) {
      init()
    }
  },
)

/** 离开页面时中断进行中的流,并移除可视化编辑消息监听 */
onBeforeUnmount(() => {
  sendAbort?.abort()
  stopPreviewTimer()
  window.removeEventListener('message', onVisualEditMessage)
  // 退出页面时若仍处于编辑模式,通知 iframe 关闭
  notifyEditMode(previewFrame.value, false)
})

// 调试钩子:暴露对话页流式状态供端到端测试读取(读取即最新值,不影响运行时)
;(window as any).__chatDebug = {
  get state() {
    return {
      appUpdating: appUpdating.value,
      codeLen: codeStreamRaw.value.length,
      displayLen: displayCode.value.length,
      previewLen: previewDoc.value.length,
      files: files.value.map((f) => f.name),
      activeFile: activeFileKey.value,
      tab: activeTab.value,
    }
  },
}
</script>

<template>
  <div class="chat-page">
    <!-- 左栏:对话(顶栏 + 消息区 + 输入区) -->
    <div class="chat-col">
      <!-- 顶部:返回 + 应用名 + 查看网站 -->
      <div class="chat-head">
        <a-button type="text" class="back-btn" @click="goBack">← 返回</a-button>
        <h2 class="chat-title">{{ app?.appName || '应用对话' }}</h2>
        <span v-if="isVue" class="type-badge mono">VUE</span>
        <a-button
          v-if="showWebsite"
          type="primary"
          size="small"
          class="site-btn"
          @click="openWebsite"
        >
          查看网站 ↗
        </a-button>
        <span v-else class="site-placeholder" />
      </div>

      <!-- 消息列表 -->
      <div ref="bodyRef" class="chat-body">
        <div v-if="hasMore" class="load-more-wrap">
          <a-button size="small" :loading="loadMoreLoading" @click="loadMore">加载更多</a-button>
        </div>

        <div v-if="historyLoading" class="state">加载对话中...</div>
        <div v-else-if="denied" class="state">无权限查看该应用的对话</div>
        <div v-else-if="messages.length === 0" class="state">
          {{ isOwnApp ? '这是一个新应用,正在准备首次对话...' : '暂无对话记录' }}
        </div>

        <div v-else class="msg-list">
          <div
            v-for="m in messages"
            :key="m.localId"
            class="msg-row"
            :class="[m.messageType, { streaming: m.streaming }]"
          >
            <div class="avatar">{{ m.messageType === 'user' ? '我' : m.messageType === 'error' ? '!' : 'AI' }}</div>
            <div class="bubble-wrap">
              <div class="bubble">
                <span class="bubble-text">{{ m.message }}</span>
                <span v-if="m.streaming" class="typing">▋</span>
              </div>
              <div class="bubble-time">{{ fmtTime(m.createTime) }}</div>
            </div>
          </div>
          <div v-if="waitingAi" class="waiting">助手思考中...</div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="chat-foot">
        <!-- 可视化编辑:选中网页元素后提示;有文字时可直接改文字并保存(直接替换代码,不调 AI) -->
        <a-alert
          v-if="selectedElement"
          type="info"
          show-icon
          closable
          class="edit-alert"
          @close="clearSelection"
        >
          <template #message>
            <div class="edit-alert-block">
              <span class="edit-alert-inner">
                <span class="edit-alert-label">已选中元素</span>
                <code class="edit-alert-sel">{{ formatElementLabel(selectedElement) }}</code>
              </span>
              <div v-if="selText" class="text-edit-row">
                <a-input
                  v-model:value="textDraft"
                  class="text-edit-input"
                  size="small"
                  :maxlength="500"
                  placeholder="直接修改该元素的文字,保存后同步到代码"
                />
              </div>
              <div class="style-edit-row">
                <label class="style-edit-item">
                  <span class="style-edit-name">颜色</span>
                  <input
                    type="color"
                    class="style-color"
                    :value="colorDraft || '#000000'"
                    @input="onColorInput"
                  />
                </label>
                <label class="style-edit-item">
                  <span class="style-edit-name">内边距</span>
                  <a-input-number
                    :value="numOf(paddingDraft)"
                    :min="0"
                    :max="999"
                    size="small"
                    class="style-num"
                    placeholder="px"
                    @change="onPaddingChange"
                  />
                </label>
                <label class="style-edit-item">
                  <span class="style-edit-name">外边距</span>
                  <a-input-number
                    :value="numOf(marginDraft)"
                    :min="0"
                    :max="999"
                    size="small"
                    class="style-num"
                    placeholder="px"
                    @change="onMarginChange"
                  />
                </label>
              </div>
              <div class="style-edit-save-row">
                <a-button type="primary" size="small" :loading="visualSaving" @click="saveVisualEdit">
                  保存修改
                </a-button>
                <span class="style-edit-hint">文字 / 颜色 / 边距直接改,不调 AI</span>
              </div>
            </div>
          </template>
        </a-alert>

        <a-textarea
          v-model:value="inputText"
          class="chat-input"
          :rows="2"
          :disabled="!canChat"
          :placeholder="canChat ? '和你的应用聊聊,让它帮你调整、说明功能...' : '仅应用创建者与管理员可对话'"
          :maxlength="500"
          @press-enter="!sending && sendMessage(inputText)"
        />
        <div class="foot-right">
          <span v-if="sending" class="sending-hint">{{
            isVue ? '正在生成 Vue 项目...' : (appUpdating ? '正在更新应用预览...' : 'AI 回复中...')
          }}</span>
          <!-- 可视化编辑入口:置于发送按钮左侧 -->
          <a-button
            class="edit-btn"
            :class="{ 'edit-mode-on': editMode }"
            :disabled="!canChat || previewMode !== 'srcdoc'"
            :title="previewMode !== 'srcdoc' ? '可视化编辑仅支持实时预览' : (editMode ? '退出编辑模式' : '开启可视化编辑')"
            @click="toggleEditMode"
          >
            <template #icon><span class="edit-icon">{{ editMode ? '✓' : '✎' }}</span></template>
            {{ editMode ? '退出编辑' : '编辑' }}
          </a-button>
          <a-button
            type="primary"
            class="send-btn"
            :loading="sending"
            :disabled="!canChat"
            @click="sendMessage(inputText)"
          >
            发送
          </a-button>
        </div>
      </div>
    </div><!-- /chat-col -->

    <!-- 右栏:实时预览 / 流式代码(代码与预览均随 codegen 流式更新) -->
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
        <span v-if="appUpdating" class="live-badge">{{ isVue ? 'BUILDING' : 'WRITING' }}</span>
        <span v-else-if="previewMode !== 'empty'" class="live-badge">LIVE</span>
      </div>

      <!-- 预览 tab:流式期间 400ms 节流刷新,appUpdated 后为结构化 srcdoc / 已部署 iframe -->
      <iframe
        ref="previewFrame"
        v-if="activeTab === 'preview' && previewMode === 'srcdoc'"
        class="frame"
        :srcdoc="previewDoc"
        sandbox="allow-scripts allow-modals"
        title="应用实时预览"
        @load="onPreviewFrameLoad"
      />
      <!-- Vue 深度开发生成中:不做花哨界面,只显示真实字符串进度(目录 + 已写入文件数 + 最新文件) -->
      <div v-else-if="activeTab === 'preview' && appUpdating && isVue" class="vue-progress">
        <div class="vue-progress-line">正在生成 Vue 项目… 已写入 {{ vueFiles.length }} 个文件</div>
        <div v-if="vueFiles.length" class="vue-progress-line mono">→ {{ vueFiles[vueFiles.length - 1] }}</div>
      </div>
      <!-- 部署中:只显示实时部署进度字符串(阶段 + npm 输出),无多余标语 -->
      <div v-else-if="activeTab === 'preview' && deploying" class="vue-progress">
        <div v-if="deployMsg" class="vue-progress-line mono">{{ deployMsg }}</div>
      </div>
      <iframe
        v-else-if="activeTab === 'preview' && previewMode === 'iframe'"
        class="frame"
        :src="previewSrc"
        sandbox="allow-scripts allow-modals allow-same-origin"
        title="应用实时预览"
      />
      <div v-else-if="activeTab === 'preview'" class="preview-empty">
        <div class="preview-empty-icon">🖥️</div>
        <div class="preview-empty-text">{{ isVue ? '项目已生成,部署后即可在线预览' : '应用尚未部署,部署后即可实时预览' }}</div>
      </div>

      <!-- 代码 tab:流式中终端打字机,完成后分文件 -->
      <div v-else class="code-pane">
        <template v-if="appUpdating && codeStreamRaw">
          <div class="code-status mono">正在生成代码… 已输出 {{ codeStreamRaw.length }} 字符</div>
          <pre ref="streamCodeRef" class="code-block plain">{{ displayCode || '// 等待 AI 输出代码…' }}<span class="cursor-blink">▋</span></pre>
        </template>
        <!-- Vue 深度开发生成中:只显示字符串进度 + 真实落盘文件清单,不做终端界面 -->
        <div v-else-if="appUpdating && isVue" class="vue-progress code-progress">
          <div class="vue-progress-line">正在生成 Vue 项目… 已写入 {{ vueFiles.length }} 个文件</div>
          <div class="vue-files">
            <div v-for="(p, i) in vueFiles" :key="`${i}-${p}`" class="vue-file-line mono">✓ {{ p }}</div>
          </div>
        </div>
        <!-- Vue 深度开发生成完成:只保留真实落盘文件清单,无完成标语 -->
        <template v-else-if="isVue && vueDone">
          <div class="vue-progress code-progress">
            <div class="vue-files">
              <div v-for="(f, i) in files" :key="`${i}-${f.name}`" class="vue-file-line mono">✓ {{ f.name }}</div>
            </div>
          </div>
        </template>
        <template v-else-if="!isVue && files.length">
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
        <div v-else class="preview-empty">
          <div class="preview-empty-icon">⌨️</div>
          <div class="preview-empty-text">{{
            isVue ? '在左侧描述需求,AI 将为你生成完整 Vue 项目' : '在左侧发消息,AI 生成的代码会实时出现在这里'
          }}</div>
        </div>
      </div>

      <!-- 操作栏:下载代码 / 部署上线 -->
      <div class="pane-actions">
        <button
          type="button"
          class="pane-btn mono"
          :disabled="files.length === 0"
          @click="handleDownload"
        >
          ⬇ 下载代码
        </button>
        <button
          type="button"
          class="pane-btn primary mono"
          :class="{ 'btn-loading': deploying }"
          :disabled="files.length === 0 || deploying"
          @click="handleDeploy"
        >
          <span v-if="deploying" class="spinner" />
          {{ app?.deployUrl ? '🔗 打开网站' : '🚀 部署上线' }}
        </button>
      </div>

      <transition name="fade">
        <div v-if="appUpdating" class="updating-banner">
          <span class="live-dot" />
          <span class="mono">{{ isVue ? '正在生成 Vue 项目,完成后可部署预览…' : '正在重写应用代码,预览实时同步…' }}</span>
          <span v-if="deployMsg" class="deploy-msg mono">→ {{ deployMsg }}</span>
        </div>
      </transition>
    </aside>
  </div>
</template>

<style scoped>
.chat-page {
  /* 左对话 + 右实时预览 两栏;外层高度不变,左右各自内部滚动 */
  display: grid;
  grid-template-columns: minmax(380px, 46%) 1fr;
  gap: 16px;
  height: calc(100vh - 190px);
  min-height: 480px;
  max-width: 1120px;
  margin: 0 auto;
}

/* 左栏:对话(顶栏 + 消息区 + 输入区,纵向排布) */
.chat-col {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

/* 右栏:实时预览(已部署应用 iframe / 未部署占位) */
.preview-pane {
  position: relative;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
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
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

.tab:hover {
  color: var(--text);
  background: rgba(21, 27, 38, 0.05);
}

.tab.active {
  color: #04120c;
  background: var(--gradient);
  font-weight: 600;
  box-shadow: 0 0 6px rgba(0, 255, 157, 0.14);
}

.live-badge {
  font-size: 10px;
  color: var(--success);
  border: 1px solid rgba(52, 211, 153, 0.4);
  background: rgba(52, 211, 153, 0.08);
  border-radius: 6px;
  padding: 1px 7px;
}

/* 代码面板(终端深色,统一全局 hljs 暗色 token) */
.code-pane {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #060a10;
}

/* 非 Vue 流式代码顶部的字符串状态(替换终端条,纯文字反馈) */
.code-status {
  flex-shrink: 0;
  padding: 8px 16px;
  font-size: 11px;
  color: var(--text-3);
  border-bottom: 1px solid var(--border);
  background: rgba(0, 0, 0, 0.25);
}

.file-tabs {
  display: flex;
  gap: 2px;
  padding: 8px 10px 0;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
  background: rgba(0, 0, 0, 0.25);
}

.file-tab {
  border: 1px solid transparent;
  border-bottom: none;
  background: transparent;
  color: var(--text-2);
  padding: 5px 12px;
  border-radius: 8px 8px 0 0;
  font-size: 12px;
  cursor: pointer;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

.file-tab:hover {
  color: var(--text);
}

.file-tab.active {
  background: rgba(0, 255, 157, 0.1);
  color: var(--primary);
  border-color: rgba(0, 255, 157, 0.3);
}

.code-block {
  flex: 1;
  margin: 0;
  padding: 14px 16px;
  overflow: auto;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.65;
  white-space: pre;
}

.code-block.plain {
  color: var(--text);
}

/* 操作栏:下载 / 部署(文件就绪后才可用) */
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

.pane-btn {
  height: 30px;
  padding: 0 14px;
  border: 1px solid var(--border-strong);
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.25);
  color: var(--text-2);
  font-size: 12px;
  cursor: pointer;
  letter-spacing: 0.3px;
  transition: all 0.18s;
}

.pane-btn:hover:not(:disabled) {
  border-color: rgba(0, 255, 157, 0.5);
  color: var(--primary);
}

.pane-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.pane-btn.primary {
  border-color: rgba(0, 255, 157, 0.45);
  color: var(--primary);
  background: rgba(0, 255, 157, 0.07);
}

.frame {
  flex: 1;
  width: 100%;
  border: none;
  background: #fff;
}

.preview-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 20px;
  color: var(--text-3);
  text-align: center;
}

.preview-empty-icon {
  font-size: 40px;
}

.preview-empty-text {
  font-size: 13px;
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

/* Vue 深度开发生成中 / 部署中:纯字符串进度(不做花哨终端界面,只有真实文字反馈) */
.vue-progress {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 20px 22px;
  background: #060a10;
  color: var(--text-2);
  font-size: 13px;
  line-height: 1.7;
}

.vue-progress-line {
  word-break: break-all;
}

/* 代码 tab 里的文件清单可滚动 */
.vue-files {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-top: 4px;
  padding-top: 10px;
  border-top: 1px dashed rgba(0, 255, 157, 0.15);
}

.vue-file-line {
  font-size: 12px;
  color: var(--text-3);
  word-break: break-all;
}

/* 自动应用修改的提示条(浮在预览区底部,终端绿) */
.updating-banner {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 10px;
  background: rgba(0, 255, 157, 0.08);
  border: 1px solid rgba(0, 255, 157, 0.35);
  color: var(--primary);
  font-size: 12px;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  backdrop-filter: blur(6px);
}

/* 自动重新部署期间的实时进度字符串(阶段 + npm 输出,随 SSE 逐行更新) */
.deploy-msg {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-2);
  font-size: 11px;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* 小屏:隐藏预览,对话占满(保持旧版单栏体验) */
@media (max-width: 900px) {
  .chat-page {
    grid-template-columns: 1fr;
  }
  .preview-pane {
    display: none;
  }
}

/* ---------- 顶栏 ---------- */
.chat-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--border);
}

.back-btn {
  color: var(--text-2);
}

.chat-title {
  flex: 1;
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.site-btn {
  flex-shrink: 0;
}

/* 深度开发(Vue)类型角标:与 LIVE/WRITING 同风格的终端绿小标签 */
.type-badge {
  flex-shrink: 0;
  font-size: 10px;
  color: var(--success);
  border: 1px solid rgba(52, 211, 153, 0.4);
  background: rgba(52, 211, 153, 0.08);
  border-radius: 6px;
  padding: 1px 7px;
  letter-spacing: 0.5px;
}

.site-placeholder {
  width: 74px;
}

/* ---------- 消息区 ---------- */
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 18px 4px 8px;
  display: flex;
  flex-direction: column;
}

.state {
  margin: auto;
  text-align: center;
  color: var(--text-3);
  font-size: 14px;
  padding: 40px 20px;
}

.load-more-wrap {
  text-align: center;
  padding-bottom: 12px;
}

.msg-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
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

.msg-row.error .avatar {
  background: linear-gradient(135deg, #f59e0b, #ef4444);
}

/* 用户消息右对齐 */
.msg-row.user {
  flex-direction: row-reverse;
}

.bubble-wrap {
  max-width: 78%;
  display: flex;
  flex-direction: column;
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

.msg-row.error .bubble {
  background: rgba(245, 158, 11, 0.1);
  border-color: rgba(245, 158, 11, 0.35);
  color: #b45309;
}

.bubble-text {
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.typing {
  display: inline-block;
  animation: blink 1s steps(2, start) infinite;
  color: inherit;
}

.bubble-time {
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-3);
  padding: 0 4px;
}

.waiting {
  text-align: center;
  color: var(--text-3);
  font-size: 12px;
  padding-top: 4px;
}

/* ---------- 输入区 ---------- */
.chat-foot {
  border-top: 1px solid var(--border);
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* 可视化编辑:选中元素的提示条(输入框上方) */
.edit-alert {
  font-size: 12px;
}

/* 选中元素提示 + 「直接改文字」编辑行的纵向容器 */
.edit-alert-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.edit-alert-inner {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

/* 「直接改文字」行:编辑框 */
.text-edit-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.text-edit-input {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  background: var(--panel) !important;
}

/* 「直接改样式」行:颜色 + 内边距 + 外边距(所见即所得,不调 AI) */
.style-edit-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.style-edit-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.style-edit-name {
  color: var(--text-2);
  flex-shrink: 0;
}

.style-color {
  width: 32px;
  height: 24px;
  padding: 1px;
  border: 1px solid var(--border-strong);
  border-radius: 6px;
  background: var(--panel);
  cursor: pointer;
}

.style-num {
  width: 76px;
}

/* 保存按钮 + 提示行 */
.style-edit-save-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.style-edit-hint {
  font-size: 11px;
  color: var(--text-3);
}

.edit-alert-label {
  color: var(--text-2);
  flex-shrink: 0;
}

.edit-alert-sel {
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 11px;
  color: var(--primary);
  background: rgba(0, 255, 157, 0.08);
  border: 1px solid rgba(0, 255, 157, 0.25);
  border-radius: 6px;
  padding: 1px 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

.chat-input {
  font-size: 14px;
  line-height: 1.6;
  background: var(--panel) !important;
}

.foot-right {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}

.sending-hint {
  font-size: 12px;
  color: var(--text-3);
}

/* 可视化编辑入口按钮(置于发送按钮左侧):开启态绿色高亮 */
.edit-btn {
  border-color: var(--border-strong);
  color: var(--text-2);
}

.edit-btn:not(:disabled):hover {
  border-color: rgba(0, 255, 157, 0.5);
  color: var(--primary);
}

.edit-btn.edit-mode-on,
.edit-btn.edit-mode-on:not(:disabled):hover {
  border-color: rgba(0, 255, 157, 0.5);
  color: var(--primary);
  background: rgba(0, 255, 157, 0.07);
}

.edit-icon {
  margin-right: 2px;
}

.send-btn {
  min-width: 88px;
}

@keyframes blink {
  to {
    visibility: hidden;
  }
}
</style>
