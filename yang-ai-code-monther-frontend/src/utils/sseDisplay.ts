/**
 * SSE 流式显示工具。
 *
 * 后端把模型输出的原始 token 增量推送给前端(streamingCode 原样累积),
 * 而模型的原始输出是带 JSON 包裹的(如 {"htmlCode":"<!DOCTYPE html>..."} ),
 * 直接显示会看到 {、htmlCode、转义符 等噪音。
 * 这里从「原始流文本」里提取出真正要展示/预览的 HTML 代码:
 *   - JSON 包裹  → 解析出 htmlCode 字段的值并反转义
 *   - Markdown 围栏 → 去掉 ```html ... ```
 *   - 裸 HTML   → 原样返回
 * 流式过程中文本未完整,提取是对当前整段累积文本重新计算的,末尾不完整的
 * 转义序列会随后续 token 到达而自愈,不影响最终(complete 后走结构化结果)。
 */

/** 统计 index 前连续反斜杠的个数(用于判断引号是否被转义) */
function countBackslashes(text: string, index: number): number {
  let n = 0
  for (let i = index - 1; i >= 0 && text[i] === '\\'; i--) {
    n++
  }
  return n
}

/**
 * 反转义 JSON 字符串的值部分(含流式中途不完整的转义)。
 * 处理 \" \\ \/ \b \f \n \r \t 和 \uXXXX;遇到不完整的转义(如末尾单个 \ )原样保留。
 */
export function decodeJsonString(value: string): string {
  let out = ''
  let i = 0
  while (i < value.length) {
    const ch = value[i]
    if (ch !== '\\') {
      out += ch
      i++
      continue
    }
    const next = value[i + 1]
    if (next === undefined) {
      // 流式末尾不完整的反斜杠:原样保留,等后续 token 到来后重新计算
      out += ch
      i++
      continue
    }
    switch (next) {
      case '"':
        out += '"'
        i += 2
        break
      case '\\':
        out += '\\'
        i += 2
        break
      case '/':
        out += '/'
        i += 2
        break
      case 'n':
        out += '\n'
        i += 2
        break
      case 't':
        out += '\t'
        i += 2
        break
      case 'r':
        out += '\r'
        i += 2
        break
      case 'b':
        out += '\b'
        i += 2
        break
      case 'f':
        out += '\f'
        i += 2
        break
      case 'u': {
        const hex = value.slice(i + 2, i + 6)
        if (/^[0-9a-fA-F]{4}$/.test(hex)) {
          out += String.fromCharCode(parseInt(hex, 16))
          i += 6
        } else {
          // \u 后四位数还没到齐,原样保留
          out += ch
          i++
        }
        break
      }
      default:
        // 非标准转义,原样保留
        out += ch
        i++
        break
    }
  }
  return out
}

/**
 * 剥掉文本里的 Markdown 代码围栏块(```lang\n...\n```),只保留其余正文。
 * 用于「对话框不展示代码」:即使 AI 回复误输出代码块,气泡也只显示对话正文,
 * 代码本体在右侧「代码」区 / 预览里看。存储的消息原文不变,这里只影响展示。
 * 不处理单反引号内联代码,避免误伤正文里的短引用。
 * 不完整围栏(流式中途/异常,有 ``` 无闭合)从起始 ``` 起删到文本末尾。
 */
export function stripFencedCodeBlocks(text: string): string {
  if (!text || !text.includes('```')) return text
  return text.replace(/```[\s\S]*?(```|$)/g, '').replace(/\n{3,}/g, '\n\n')
}

/**
 * 从原始流文本中提取 HTML 代码(用于生成页代码区展示 + iframe 实时预览)。
 *
 * 兼容模型输出的三种形态:
 *   - ```json {"htmlCode":"..."} ```  (Markdown 围栏 + JSON)
 *   - ```html <code> ```              (Markdown 围栏 + 裸 HTML)
 *   - {"htmlCode":"..."}              (裸 JSON)
 *   - <!DOCTYPE html>...              (裸 HTML)
 *
 * @param raw 累积的原始流文本(可能处于生成中途)
 * @returns 干净的 HTML 代码;还没解析出有效内容时返回空串或原文
 */
export function extractHtmlCode(raw: string): string {
  let t = raw.trimStart()

  // 1) 去掉 Markdown 代码围栏(任意语言标签,如 ```json / ```html / ```):
  //    只吃开围栏的标记部分,内容留到后面处理,结尾的 ``` 再单独剥掉。
  //    注意语言标签后可能紧跟 { 而没有换行(如 ```json{"htmlCode":...}),
  //    所以围栏匹配到「换行或 { 或 ` 」就停,不能贪婪吃掉后面的 JSON 内容。
  const fenceOpen = /^```[^\n{`]*\n?/.exec(t)
  if (fenceOpen) {
    t = t.slice(fenceOpen[0].length)
    t = t.replace(/```\s*$/, '')
  }

  // 2) JSON 包裹:{"htmlCode":"...", "description":"..."}
  const keyMatch = /"htmlCode"\s*:\s*"/.exec(t)
  if (keyMatch && keyMatch.index !== undefined) {
    const start = keyMatch.index + keyMatch[0].length
    const tail = t.slice(start)
    // 找 htmlCode 值的结束引号(跳过被转义的 \" )
    let end = -1
    for (let i = 0; i < tail.length; i++) {
      if (tail[i] === '"' && countBackslashes(tail, i) % 2 === 0) {
        end = i
        break
      }
    }
    const value = end >= 0 ? tail.slice(0, end) : tail
    return decodeJsonString(value)
  }

  // 3) JSON 刚开始但 htmlCode 键还没到:先显示空,避免闪出 { 或 {\\ 等噪音
  if (/^\{/.test(t)) {
    return ''
  }

  // 4) 裸 HTML:原样返回(已剥掉可能的围栏)
  return t
}
