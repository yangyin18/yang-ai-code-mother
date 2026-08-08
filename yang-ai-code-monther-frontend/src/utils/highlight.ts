/**
 * 代码高亮工具(基于 highlight.js)
 *
 * 按需注册少量语言,控制打包体积(而不是引入 highlight.js 全量)。
 * .vue 单文件组件用 xml 语法高亮(标签部分直观好看),js/ts/css 各按扩展名匹配。
 */
import hljs from 'highlight.js/lib/core'
import xml from 'highlight.js/lib/languages/xml'
import css from 'highlight.js/lib/languages/css'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'

hljs.registerLanguage('xml', xml)
hljs.registerLanguage('css', css)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('typescript', typescript)

/** 根据文件名推断高亮语言 */
function langOf(fileName: string): string {
  if (fileName.endsWith('.vue') || fileName.endsWith('.html') || fileName.endsWith('.xml')) {
    return 'xml'
  }
  if (fileName.endsWith('.css')) return 'css'
  if (fileName.endsWith('.ts')) return 'typescript'
  return 'javascript'
}

/**
 * 高亮代码,返回带 <span class="hljs-*"> 的 HTML 片段
 * 调用方用 v-html 渲染(highlight.js 输出已做过 HTML 转义,安全)。
 */
export function highlight(code: string, fileName: string): string {
  try {
    return hljs.highlight(code, { language: langOf(fileName) }).value
  } catch {
    // 兜底:至少做 HTML 转义,防止注入
    return code.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  }
}
