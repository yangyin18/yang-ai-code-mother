/**
 * 对话相关接口(真实后端)
 *
 * 对话闭环:发送消息(POST /chat/send,SSE 流式 AI 回复,自动落库 user/ai/error)
 *         → 游标加载历史(GET /chat/cursor/list,keyset 分页)。
 * 均需要登录(@AuthCheck,走 session Cookie)。
 */
import { request } from './request'

/** 后端 ChatHistoryVO(一条对话消息) */
export interface ChatMessage {
  id: string
  appId: string
  userId: string
  messageType: 'user' | 'ai' | 'error'
  message: string
  createTime?: string
  updateTime?: string
}

/** 后端 ChatCursorVO(游标分页结果) */
export interface ChatCursorVO {
  records: ChatMessage[]
  hasMore: boolean
}

/** 管理员分页结果(MyBatis-Flex Page 序列化) */
export interface ChatPage {
  records: ChatMessage[]
  totalRow: number
  totalPage: number
  pageNumber: number
  pageSize: number
}

/**
 * appUpdated SSE 事件负载:「对话即改代码」完成后推给前端刷新预览。
 * 已部署应用带 deployUrl;未部署应用 deployUrl 为 null,前端用 html/css/js 拼 srcdoc 预览。
 */
export interface AppUpdatedPayload {
  fileNames?: string[]
  htmlCode?: string
  cssCode?: string
  jsCode?: string
  deployUrl?: string | null
  updateTime?: string
}

/** 后端 ChatConversationVO(我的对话会话摘要) */
export interface ChatConversation {
  appId: string
  appName?: string
  cover?: string
  deployUrl?: string | null
  latestMessage?: string
  latestMessageType?: 'user' | 'ai' | 'error'
  latestTime?: string
  messageCount?: number
}

/**
 * 游标加载某个应用的对话历史(keyset 分页)。
 * 后端按 createTime DESC + id DESC 返回「比 cursorId 更早」的一页,每页默认 10 条;
 * 首次进入不传 cursorId 取最新一页。由页面倒序后升序展示。
 *
 * @param appId    应用 id
 * @param cursorId 游标 id(加载比它更早的历史);首次加载不传
 * @param size     每页条数,默认 10
 */
export async function loadChatHistory(appId: string, cursorId?: string, size = 10): Promise<ChatCursorVO> {
  return request<ChatCursorVO>({
    url: '/chat/cursor/list',
    method: 'GET',
    params: { appId, cursorId, size },
  })
}

/**
 * 发送消息并流式接收 AI 回复(SSE,POST)。
 *
 * 后端流程:落库用户消息(user) → 组装上下文 → 流式生成 AI 回复 → 落库(ai/error)。
 * 事件:
 *   started   连接建立
 *   heartbeat 每 5s 保活(首 token 前)
 *   message   增量文本(AI 回复正文,直接 append 即可,不能 trim 空格)
 *   complete  落库的 AI 消息记录(JSON,含 id/createTime),resolve 时返回
 *   error     AI 回复失败(已落库 error 消息)
 *   appUpdating 文字回复后开始自动改代码(对话即改代码)
 *   codeChunk 代码流式增量(原始 token,直接 append 到流式代码区,不能 trim)
 *   progress 重新部署(已部署应用)期间的阶段 + npm 输出(逐行字符串反馈)
 *   appUpdated 代码生成完成(JSON,含新代码 / 部署地址)
 * 失败时以非 SSE 的 JSON 错误返回,或 error 事件,统一 reject。
 *
 * @param appId     应用 id
 * @param message   用户消息
 * @param onPartial 收到一个增量文本 chunk 时回调(直接 append 到 AI 气泡)
 * @param opts      { signal: 取消信号, onStarted: 连接建立回调, onHeartbeat: 心跳回调,
 *                    onAppUpdating: 开始改代码, onCodeChunk: 代码增量,
 *                    onFile: Vue 深度开发每个文件写入(真实 writeFile 工具调用,带路径),
 *                    onProgress: 重新部署进度字符串(阶段 + npm 输出), onAppUpdated: 改完 }
 * @returns 落库后的 AI 消息记录(含 id / createTime)
 */
export async function sendChatMessage(
  appId: string,
  message: string,
  onPartial: (chunk: string) => void,
  opts?: {
    signal?: AbortSignal
    onStarted?: () => void
    onHeartbeat?: (elapsedMs: number) => void
    onAppUpdating?: () => void
    onCodeChunk?: (chunk: string) => void
    onFile?: (path: string) => void
    onProgress?: (msg: string) => void
    onAppUpdated?: (payload: AppUpdatedPayload) => void
  },
): Promise<ChatMessage> {
  const resp = await fetch('/api/chat/send', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ appId, message }),
    signal: opts?.signal,
  })

  // 同步校验失败(未登录 / 应用不存在 / 无权限):后端返回普通 JSON 错误,不是 SSE
  const contentType = resp.headers.get('content-type') ?? ''
  if (!resp.ok || !resp.body || !contentType.includes('text/event-stream')) {
    const err = await resp.json().catch(() => null)
    throw new Error((err as { message?: string } | null)?.message || `发送失败(${resp.status})`)
  }

  return parseChatSse(resp.body, onPartial, opts)
}

/**
 * 手动解析 SSE 流(fetch 的 ReadableStream,EventSource 不支持 POST)。
 * 复用生成流解析逻辑:data 内容原样追加,不 trimStart(空格是回复的一部分)。
 * 事件顺序:started → heartbeat* → message*(AI 回复正文) → complete(AI 消息记录)
 *          → appUpdating(开始自动改代码) → codeChunk*(代码流式增量)
 *          → appUpdated(改完,带新代码/新部署地址) → 流结束。
 * 注意:complete 在前、codeChunk/appUpdated 在后且流未关,读循环会继续派发它们,
 * 所以 Promise 在流关闭后才 resolve,await 天然覆盖代码重新生成的耗时。
 */
async function parseChatSse(
  body: ReadableStream<Uint8Array>,
  onPartial: (chunk: string) => void,
  opts?: {
    signal?: AbortSignal
    onStarted?: () => void
    onHeartbeat?: (elapsedMs: number) => void
    onAppUpdating?: () => void
    onCodeChunk?: (chunk: string) => void
    onFile?: (path: string) => void
    onProgress?: (msg: string) => void
    onAppUpdated?: (payload: AppUpdatedPayload) => void
  },
): Promise<ChatMessage> {
  const reader = body.getReader()
  const decoder = new TextDecoder()

  return new Promise<ChatMessage>((resolve, reject) => {
    let buffer = ''
    let resolved: ChatMessage | null = null
    const onAbort = () => reject(new Error('已取消发送'))
    opts?.signal?.addEventListener('abort', onAbort)

    function dispatchEvent(raw: string) {
      let eventName = 'message'
      let data = ''
      for (const line of raw.split('\n')) {
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          // 不能 trimStart:空格是回复正文的一部分
          data += (data ? '\n' : '') + line.slice(5)
        }
      }
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
        resolved = JSON.parse(data) as ChatMessage
      } else if (eventName === 'appUpdating') {
        // appUpdating 只是「开始改代码」的信号,不携带任何目录/路径信息
        opts?.onAppUpdating?.()
      } else if (eventName === 'codeChunk') {
        opts?.onCodeChunk?.(data)
      } else if (eventName === 'file') {
        // Vue 深度开发:每个文件真实写入后推送 {"path":"..."},展示真实工具调用
        try {
          opts?.onFile?.((JSON.parse(data) as { path?: string }).path ?? '')
        } catch {
          /* 文件路径解析失败不影响主流程 */
        }
      } else if (eventName === 'progress') {
        // 重新部署(已部署应用自动更新)期间的阶段 + npm 输出逐行反馈
        opts?.onProgress?.(data)
      } else if (eventName === 'appUpdated') {
        opts?.onAppUpdated?.(JSON.parse(data) as AppUpdatedPayload)
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
        if (resolved) {
          resolve(resolved)
        } else {
          reject(new Error('回复流意外结束,请重试'))
        }
      } catch (e) {
        reject(e)
      } finally {
        opts?.signal?.removeEventListener('abort', onAbort)
      }
    })()
  })
}

/** 管理员查询条件 */
export interface ChatAdminQuery {
  pageNum?: number
  pageSize?: number
  appId?: string
  userId?: string
  messageType?: string
  sortField?: string
  sortOrder?: string
}

/**
 * 管理员分页查询全部应用的对话历史(默认按创建时间倒序,便于内容监管)
 */
export async function adminListChatHistory(query: ChatAdminQuery = {}): Promise<ChatPage> {
  return request<ChatPage>({
    url: '/chat/admin/list/page',
    method: 'GET',
    params: query,
  })
}

/**
 * 查询当前用户的「我的对话」会话列表:每个应用的最新消息摘要,
 * 按最近活跃倒序。GET /chat/my/conversations
 */
export async function listMyConversations(): Promise<ChatConversation[]> {
  const list = await request<ChatConversation[]>({
    url: '/chat/my/conversations',
    method: 'GET',
  })
  return list ?? []
}
