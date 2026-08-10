/**
 * 生成相关接口(真实后端)
 *
 * 完整闭环:创建应用(POST /app/add) → AI 流式生成(POST /app/generate/stream,SSE)
 *          → 部署到 nginx(POST /app/deploy) → 查详情拿访问地址(GET /app/get)。
 * 均需要登录(@AuthCheck,走 session Cookie)。
 */
import { request } from './request'
import type { CodeGenPayload, GenerateStep } from '@/types'

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
  saveDir?: string
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
  createTime?: string
  updateTime?: string
}

/** 后端分页结果(MyBatis-Flex Page 序列化) */
export interface Page<T> {
  records: T[]
  total: number
  current?: number
  size?: number
}

/** 后端 DeployResult(部署结果) */
interface DeployResultVO {
  appId: string
  deployKey: string
  deployUrl: string
}

/**
 * 创建应用
 *
 * @param appName     应用名称
 * @param requirement 应用初始化的 prompt(也是本次需求描述)
 * @returns 新应用 id(雪花 ID 已由后端序列化为字符串,避免 JS 精度丢失)
 */
export async function createApp(appName: string, requirement: string): Promise<string> {
  return request<string>({
    url: '/app/add',
    method: 'POST',
    data: { appName, initPrompt: requirement, codeGenType: 'html' },
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

  // 同步校验失败(应用不存在 / 未登录等):后端返回普通 JSON 错误,不是 SSE
  const contentType = resp.headers.get('content-type') ?? ''
  if (!resp.ok || !resp.body || !contentType.includes('text/event-stream')) {
    const err = await resp.json().catch(() => null)
    throw new Error((err as { message?: string } | null)?.message || `生成失败(${resp.status})`)
  }

  return parseSse(resp.body, onPartial, opts)
}

/**
 * 查询"我的应用"列表(分页,按创建时间倒序)
 */
export async function listMyApps(current = 1, size = 12): Promise<AppVO[]> {
  const page = await request<Page<AppVO>>({
    url: '/app/my/list/page',
    method: 'GET',
    params: { current, size },
  })
  return page?.records ?? []
}

/**
 * 查询"精选应用"列表(应用广场,分页)。
 * 精选 = 已部署(deployKey 非空)的应用,按优先级、创建时间倒序,每页最多 20 个。
 */
export async function listFeaturedApps(current = 1, size = 12): Promise<AppVO[]> {
  const page = await request<Page<AppVO>>({
    url: '/app/featured/list/page',
    method: 'GET',
    params: { current, size },
  })
  return page?.records ?? []
}

/**
 * 手动解析 SSE 流(fetch 的 ReadableStream,不支持 EventSource 的 POST)。
 * 事件格式(Spring SseEmitter):
 *   event: started      (连接建立,LLM 尚未出首 token)
 *   event: heartbeat    (每 5s 一次保活,数据为 {"elapsedMs":...})
 *   event: message      (增量文本 chunk —— 唯一的代码流)
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
    saveDir: vo.saveDir ?? '',
  }
}

/**
 * 查询应用详情(拿后端权威的 appName / deployUrl)
 */
export async function getAppDetail(appId: string): Promise<AppVO> {
  return request<AppVO>({ url: '/app/get', method: 'GET', params: { id: appId } })
}

/**
 * 把已生成代码部署到 nginx,返回访问地址
 */
export async function deployApp(appId: string): Promise<string> {
  const res = await request<DeployResultVO>({
    url: '/app/deploy',
    method: 'POST',
    data: { id: appId },
  })
  return res.deployUrl
}

/** 管理员查询条件(应用管理) */
export interface AdminAppQuery {
  pageNum?: number
  pageSize?: number
  appName?: string
  userId?: string
  priority?: number
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

/** 管理员更新应用(名称/封面/优先级) POST /app/admin/update */
export async function adminUpdateApp(req: {
  id: string
  appName?: string
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
