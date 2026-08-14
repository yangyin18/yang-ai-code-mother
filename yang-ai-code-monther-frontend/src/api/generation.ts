/**
 * 生成相关接口(真实后端)
 *
 * 完整闭环:创建应用(POST /app/add) → AI 流式生成(POST /app/generate/stream,SSE)
 *          → 部署到 nginx(POST /app/deploy) → 查详情拿访问地址(GET /app/get)。
 * 均需要登录(@AuthCheck,走 session Cookie)。
 */
import { request } from './request'
import type { CodeGenPayload, GenerateStep, ProjectFile } from '@/types'

/** 生成过程的固定步骤(生成页按这个顺序逐条展示) */
export const GENERATE_STEPS: GenerateStep[] = [
  { key: 'understand', title: '理解需求', desc: '分析你的需求描述,提取核心功能点' },
  { key: 'code', title: 'AI 生成代码', desc: '正在边生成边输出,实时预览' },
  { key: 'done', title: '组装完成', desc: '打包校验,准备上线' },
]

/** 后端 CodeGenResult(代码生成结果) */
interface CodeGenResultVO {
  codeGenType: string
  description?: string
  htmlCode?: string
  cssCode?: string
  jsCode?: string
  fileNames?: string[]
}

/** 后端 AppVO(应用详情,含部署地址) */
export interface AppVO {
  id: string
  appName?: string
  cover?: string
  initPrompt?: string
  codeGenType?: string
  deployKey?: string
  deployUrl?: string
  deployedTime?: string
  priority?: number
  userId?: string
  /** 创建用户账号(仅管理端列表回填,其余列表为空) */
  ownerName?: string
  createTime?: string
  updateTime?: string
}

/** 后端分页结果(MyBatis-Flex Page 序列化) */
export interface Page<T> {
  records: T[]
  /** MyBatis-Flex Page 序列化字段:总条数(Long 被全局转成字符串) */
  totalRow: number | string
  totalPage?: number | string
  pageNumber?: number | string
  pageSize?: number | string
}

/** 后端 DeployResult(部署结果) */
interface DeployResultVO {
  appId: string
  deployKey: string
  deployUrl: string
}

/** 后端 AppCodeVO(应用代码,查看代码弹窗) */
export interface AppCodeVO {
  id: string
  appName?: string
  deployUrl?: string
  codeGenType?: string
  fileNames?: string[]
  htmlCode?: string
  cssCode?: string
  jsCode?: string
  /** 完整文件清单(path + content,html/multi_file/vue 通用),前端优先用它渲染 */
  files?: ProjectFile[]
}

/**
 * 创建应用
 *
 * @param appName      应用名称
 * @param requirement  应用初始化的 prompt(也是本次需求描述)
 * @param codeGenType  生成类型:html=快速开发(原生 HTML),vue=深度开发(Vue 项目),默认 html
 * @returns 新应用 id(雪花 ID 已由后端序列化为字符串,避免 JS 精度丢失)
 */
export async function createApp(appName: string, requirement: string, codeGenType = 'html'): Promise<string> {
  return request<string>({
    url: '/app/add',
    method: 'POST',
    data: { appName, initPrompt: requirement, codeGenType },
  })
}

/**
 * 流式生成应用代码(SSE,POST)。
 *
 * 后端把生成的代码按增量文本以 SSE {@code message} 事件推送,
 * 前端把每个增量 chunk 追加到自己的缓冲区(详见控制器注释:不能整段 trim,否则破坏代码)。
 * 全部完成后推送 {@code complete} 事件(完整 CodeGenResult),解析后 resolve。
 * 失败时以非 SSE 的 JSON 错误返回,或 {@code error} 事件,统一 reject。
 *
 * 连接期事件:后端在 LLM 首 token 前先推 {@code started} 再每 5s 推 {@code heartbeat},
 * 让前端能展示"已连接/等待首 token 计时",避免首 token 慢时看起来像假流式。
 *
 * @param appId      应用 id
 * @param requirement 需求描述
 * @param onPartial  收到一个增量文本 chunk 时回调(直接 append 即可)
 * @param opts       { signal: 取消信号(离开页面时 abort,避免残留流), onStarted: 连接建立回调,
 *                     onHeartbeat: 每 5s 心跳回调(参数=已等待毫秒) }
 */
export async function generateAppStream(
  appId: string,
  requirement: string,
  onPartial: (chunk: string) => void,
  opts?: {
    signal?: AbortSignal
    onStarted?: () => void
    onHeartbeat?: (elapsedMs: number) => void
  },
): Promise<CodeGenPayload> {
  const resp = await fetch('/api/app/generate/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ appId, requirement }),
    signal: opts?.signal,
  })
  const body = await expectSse(resp, '生成失败')
  return parseSse(body, onPartial, opts)
}

/**
 * Vue 深度开发:流式生成完整 Vue 项目(SSE,POST)。
 *
 * 与快速开发不同,Vue 项目由模型通过 writeFile 工具逐个落盘:
 *   message 事件   = 模型的生成计划 / 收尾文本(只作日志展示,非代码流)
 *   file 事件      = 每写成一个文件,推送 {"path":"..."}(只含相对路径,不含内容,省 token/传输)
 *   complete 事件  = CodeGenResult(fileNames 为项目相对路径列表)
 * 深度模式不向前端暴露代码内容,只展示进度与文件清单。
 *
 * @param appId      应用 id
 * @param requirement 需求描述
 * @param opts       { signal, onStarted, onHeartbeat, onPartial(计划文本), onFile(写入一个文件) }
 */
export async function generateVueProjectStream(
  appId: string,
  requirement: string,
  opts?: {
    signal?: AbortSignal
    onStarted?: () => void
    onHeartbeat?: (elapsedMs: number) => void
    onPartial?: (chunk: string) => void
    onFile?: (path: string) => void
  },
): Promise<CodeGenPayload> {
  const resp = await fetch('/api/app/generate/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ appId, requirement }),
    signal: opts?.signal,
  })
  const body = await expectSse(resp, '生成失败')
  return parseSse(body, opts?.onPartial ?? (() => {}), {
    signal: opts?.signal,
    onStarted: opts?.onStarted,
    onHeartbeat: opts?.onHeartbeat,
    onFile: opts?.onFile,
  })
}

/**
 * 校验响应是否为 SSE:同步校验失败(应用不存在 / 未登录等)时后端返回普通 JSON 错误。
 * 校验通过后返回可读流(已确保非空),方便调用方直接交给 parseSse。
 */
async function expectSse(resp: Response, failPrefix: string): Promise<ReadableStream<Uint8Array>> {
  const contentType = resp.headers.get('content-type') ?? ''
  if (!resp.ok || !resp.body || !contentType.includes('text/event-stream')) {
    const err = await resp.json().catch(() => null)
    throw new Error((err as { message?: string } | null)?.message || `${failPrefix}(${resp.status})`)
  }
  return resp.body
}

/**
 * 查询"我的应用"列表(分页,按创建时间倒序)
 */
/** 我的应用分页结果:本页记录 + 真实总数(供「我的应用」标题显示全部数量) */
export interface AppListResult {
  records: AppVO[]
  total: number
}

export async function listMyApps(current = 1, size = 12): Promise<AppListResult> {
  const page = await request<Page<AppVO>>({
    url: '/app/my/list/page',
    method: 'GET',
    // 后端 PageRequest 读 pageNum/pageSize(非 current/size),按后端字段名传参
    params: { pageNum: current, pageSize: size },
  })
  return { records: page?.records ?? [], total: Number(page?.totalRow ?? 0) }
}

/**
 * 查询"精选应用"列表(应用广场,分页)。
 * 精选 = 已部署(deployKey 非空)的应用,按优先级、创建时间倒序,每页最多 20 个。
 */
export async function listFeaturedApps(current = 1, size = 12): Promise<AppVO[]> {
  const page = await request<Page<AppVO>>({
    url: '/app/featured/list/page',
    method: 'GET',
    // 后端 PageRequest 读 pageNum/pageSize,按后端字段名传参
    params: { pageNum: current, pageSize: size },
  })
  return page?.records ?? []
}

/**
 * 手动解析 SSE 流(fetch 的 ReadableStream,不支持 EventSource 的 POST)。
 * 事件格式(Spring SseEmitter):
 *   event: started      (连接建立,LLM 尚未出首 token)
 *   event: heartbeat    (每 5s 一次保活,数据为 {"elapsedMs":...})
 *   event: message      (增量文本 chunk —— 快速开发是代码流,Vue 深度开发是计划文本)
 *   event: file         (Vue 深度开发:写成一个文件,数据为 {"path":"..."},只含路径)
 *   event: complete     (CodeGenResult JSON)
 *   event: error        (失败信息)
 */
async function parseSse(
  body: ReadableStream<Uint8Array>,
  onPartial: (chunk: string) => void,
  opts?: {
    signal?: AbortSignal
    onStarted?: () => void
    onHeartbeat?: (elapsedMs: number) => void
    onFile?: (path: string) => void
  },
): Promise<CodeGenPayload> {
  const reader = body.getReader()
  const decoder = new TextDecoder()

  return new Promise<CodeGenPayload>((resolve, reject) => {
    let buffer = ''
    let resolvedPayload: CodeGenPayload | null = null
    let onAbort: (() => void) | null = null
    onAbort = () => reject(new Error('已取消生成'))
    opts?.signal?.addEventListener('abort', onAbort)

    function dispatchEvent(raw: string) {
      let eventName = 'message'
      let data = ''
      for (const line of raw.split('\n')) {
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          // 注意:不能 trimStart —— SSE 的 data: 内容就是代码本身,空格是代码的一部分
          // (缩进、标签名与属性之间的空格)。Spring SseEmitter 写的是 data: + 内容(无规范空格),
          // 直接取冒号后内容即可;一旦 trim 会把「 html」这类以空格开头的 chunk 的空格吃掉,
          // 导致流式代码缺空格(如 <!DOCTYPEhtml>、<htmllang=...>),与最终结果不一致。
          data += (data ? '\n' : '') + line.slice(5)
        }
      }
      // 连接期事件:不进代码缓冲
      if (eventName === 'started') {
        opts?.onStarted?.()
        return
      }
      if (eventName === 'heartbeat') {
        try {
          opts?.onHeartbeat?.(JSON.parse(data).elapsedMs ?? 0)
        } catch {
          /* 心跳数据解析失败不影响主流程 */
        }
        return
      }
      // Vue 深度开发:模型每通过 writeFile 写成一个文件,推一个 file 事件(只含相对路径)
      if (eventName === 'file') {
        try {
          opts?.onFile?.(JSON.parse(data).path ?? '')
        } catch {
          /* 文件路径解析失败不影响主流程 */
        }
        return
      }
      if (!data) return
      if (eventName === 'message') {
        onPartial(data)
      } else if (eventName === 'complete') {
        const vo = JSON.parse(data) as CodeGenResultVO
        resolvedPayload = toPayload(vo)
      } else if (eventName === 'error') {
        reject(new Error(data))
      }
    }

    ;(async () => {
      try {
        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          let idx: number
          // SSE 事件以空行分隔
          while ((idx = buffer.indexOf('\n\n')) !== -1) {
            const raw = buffer.slice(0, idx)
            buffer = buffer.slice(idx + 2)
            dispatchEvent(raw)
          }
        }
        if (resolvedPayload) {
          resolve(resolvedPayload)
        } else {
          reject(new Error('生成流意外结束,请重试'))
        }
      } catch (e) {
        reject(e)
      } finally {
        opts?.signal?.removeEventListener('abort', onAbort)
      }
    })()
  })
}

/** 后端 CodeGenResult 转前端 CodeGenPayload(appId 由调用方补上) */
function toPayload(vo: CodeGenResultVO): CodeGenPayload {
  return {
    appId: '',
    description: vo.description ?? '',
    htmlCode: vo.htmlCode ?? '',
    cssCode: vo.cssCode ?? '',
    jsCode: vo.jsCode ?? '',
    fileNames: vo.fileNames ?? [],
  }
}

/**
 * 查询应用详情(拿后端权威的 appName / deployUrl)
 */
export async function getAppDetail(appId: string): Promise<AppVO> {
  return request<AppVO>({ url: '/app/get', method: 'GET', params: { id: appId } })
}

/**
 * 查看应用已生成的代码文件(本人 / 管理员 / 已部署应用可查看)。
 * GET /app/code?id=xxx
 */
export async function getAppCode(appId: string): Promise<AppCodeVO> {
  return request<AppCodeVO>({ url: '/app/code', method: 'GET', params: { id: appId } })
}

/**
 * 直接修改应用代码里的文字(可视化编辑「选中元素 → 改文字 → 保存」,不调 AI)。
 * POST /app/code/edit-text,body: {"appId","oldText","newText"}
 * 后端把代码文件中出现的 oldText 全局替换为 newText 并写回,返回更新后的代码(刷新预览用)。
 * 权限:仅应用本人 / 管理员可改。
 */
export async function editCodeText(appId: string, oldText: string, newText: string): Promise<AppCodeVO> {
  return request<AppCodeVO>({
    url: '/app/code/edit-text',
    method: 'POST',
    data: { appId, oldText, newText },
  })
}

/**
 * 直接修改应用代码里目标元素的样式(可视化编辑「选中元素 → 改颜色/内边距/外边距 → 保存」,不调 AI)。
 * POST /app/code/edit-style,body: {"appId","tag","id","className","text","style":{"color":...,"padding":...}}
 * 后端在 index.html 里定位目标元素开标签,把 style 属性合并进其 style 属性并写回,返回更新后的代码。
 * 权限:仅应用本人 / 管理员可改。
 */
export async function editCodeStyle(req: {
  appId: string
  tag: string
  id: string
  className: string
  text: string
  style: Record<string, string>
}): Promise<AppCodeVO> {
  return request<AppCodeVO>({
    url: '/app/code/edit-style',
    method: 'POST',
    data: req,
  })
}

/**
 * 下载应用已生成的代码为 ZIP 包(Vue 是多文件目录,浏览器无法单文件下载)。
 * 后端把 {codeGenType}_{appId} 目录打包成 application/zip,前端落成浏览器下载。
 * POST /app/download,body: {"id": appId}
 */
export async function downloadAppZip(appId: string): Promise<void> {
  const resp = await fetch('/api/app/download', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ id: appId }),
  })
  if (!resp.ok) {
    const err = await resp.json().catch(() => null)
    throw new Error((err as { message?: string } | null)?.message || `下载失败(${resp.status})`)
  }
  const blob = await resp.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `app_${appId}.zip`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/**
 * 把已生成代码部署到 nginx,返回访问地址。
 * 注意:Vue 深度开发首次部署需 npm install + build(后端最长 5 分钟装依赖),
 * 必须放宽本请求超时,否则 30s 默认超时会中断部署。
 */
export async function deployApp(appId: string): Promise<string> {
  const res = await request<DeployResultVO>({
    url: '/app/deploy',
    method: 'POST',
    data: { id: appId },
    timeout: 600000,
  })
  return res.deployUrl
}

/**
 * 把已生成代码部署到 nginx,并实时流式反馈部署进度(SSE,POST)。
 *
 * 与 {@link deployApp} 效果一致,但每推一个真实进度:
 *   started   连接建立
 *   heartbeat 每 5s 保活(部署 npm install 可能长时间无输出)
 *   progress  部署阶段说明("修复缺失的模块引用…" / "安装 npm 依赖中…" /
 *             "npm run build 构建中…" / "发布到 nginx…" / "部署完成 ✓"),
 *             以及 npm 输出的每一行(实时转发)
 *   complete  DeployResult(deployUrl)
 *   error     失败原因
 *
 * @param appId 应用 id
 * @param opts  { signal, onStarted, onHeartbeat, onProgress(每个进度字符串) }
 * @returns 部署访问地址
 */
export async function deployAppStream(
  appId: string,
  opts?: {
    signal?: AbortSignal
    onStarted?: () => void
    onHeartbeat?: () => void
    onProgress?: (msg: string) => void
  },
): Promise<string> {
  const resp = await fetch('/api/app/deploy/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ id: appId }),
    signal: opts?.signal,
  })
  const body = await expectSse(resp, '部署失败')
  return parseDeploySse(body, opts)
}

/** 部署 SSE 解析:progress 逐行回调,complete 取 deployUrl resolve */
async function parseDeploySse(
  body: ReadableStream<Uint8Array>,
  opts?: {
    signal?: AbortSignal
    onStarted?: () => void
    onHeartbeat?: () => void
    onProgress?: (msg: string) => void
  },
): Promise<string> {
  const reader = body.getReader()
  const decoder = new TextDecoder()

  return new Promise<string>((resolve, reject) => {
    let buffer = ''
    let deployUrl: string | null = null
    const onAbort = () => reject(new Error('已取消部署'))
    opts?.signal?.addEventListener('abort', onAbort)

    function dispatchEvent(raw: string) {
      let eventName = 'message'
      let data = ''
      for (const line of raw.split('\n')) {
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          data += (data ? '\n' : '') + line.slice(5)
        }
      }
      if (eventName === 'started') {
        opts?.onStarted?.()
        return
      }
      if (eventName === 'heartbeat') {
        opts?.onHeartbeat?.()
        return
      }
      if (!data) return
      if (eventName === 'progress') {
        opts?.onProgress?.(data)
      } else if (eventName === 'complete') {
        const vo = JSON.parse(data) as DeployResultVO
        deployUrl = vo.deployUrl
      } else if (eventName === 'error') {
        reject(new Error(data))
      }
    }

    ;(async () => {
      try {
        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          let idx: number
          while ((idx = buffer.indexOf('\n\n')) !== -1) {
            const raw = buffer.slice(0, idx)
            buffer = buffer.slice(idx + 2)
            dispatchEvent(raw)
          }
        }
        if (deployUrl) {
          resolve(deployUrl)
        } else {
          reject(new Error('部署流意外结束,请重试'))
        }
      } catch (e) {
        reject(e)
      } finally {
        opts?.signal?.removeEventListener('abort', onAbort)
      }
    })()
  })
}

/**
 * 删除我的应用(仅创建者可删,逻辑删除;对话记录一并清理)。
 * POST /app/delete,body: {"id": appId}
 */
export async function deleteApp(appId: string): Promise<boolean> {
  return request<boolean>({
    url: '/app/delete',
    method: 'POST',
    data: { id: appId },
  })
}

/** 管理员查询条件(应用管理) */
export interface AdminAppQuery {
  pageNum?: number
  pageSize?: number
  appName?: string
  userId?: string
  priority?: number
  /** 只看精选(priority > 0,即在应用广场) */
  featuredOnly?: boolean
  sortField?: string
  sortOrder?: string
}

/** 管理员分页查询应用列表 GET /app/admin/list/page */
export async function adminListApps(query: AdminAppQuery = {}): Promise<Page<AppVO>> {
  return request<Page<AppVO>>({
    url: '/app/admin/list/page',
    method: 'GET',
    params: query,
  })
}

/**
 * 管理员新建应用卡片 POST /app/admin/create
 * 归属当前登录管理员,priority>0 即进入应用广场。
 */
export async function adminCreateApp(req: {
  appName: string
  initPrompt: string
  codeGenType?: string
  priority?: number
  cover?: string
}): Promise<string> {
  return request<string>({
    url: '/app/admin/create',
    method: 'POST',
    data: req,
  })
}

/** 管理员更新应用(名称/需求描述/封面/优先级,直接填数字) POST /app/admin/update */
export async function adminUpdateApp(req: {
  id: string
  appName?: string
  initPrompt?: string
  cover?: string
  priority?: number
}): Promise<boolean> {
  return request<boolean>({
    url: '/app/admin/update',
    method: 'POST',
    data: req,
  })
}

/** 管理员删除应用 POST /app/admin/delete */
export async function adminDeleteApp(id: string): Promise<boolean> {
  return request<boolean>({
    url: '/app/admin/delete',
    method: 'POST',
    data: { id },
  })
}
