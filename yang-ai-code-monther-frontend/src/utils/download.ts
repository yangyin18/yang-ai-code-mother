/**
 * 代码文件下载(生成页 / 结果页 / 对话页共用)。
 * 单个文件时按文件真实扩展名下载(如 index.html → xxx.html,不再拼成 txt);
 * 多文件仍拼接为 txt(保持原有行为);Vue 多文件工程走后端 zip,不经过这里。
 */

/** 取文件扩展名(.html/.css/.js/…) */
function extOf(name: string): string {
  const i = name.lastIndexOf('.')
  return i > 0 ? name.slice(i) : ''
}

/** 按扩展名给 MIME,单文件下载时让浏览器正确识别 */
function mimeOf(name: string): string {
  switch (extOf(name).toLowerCase()) {
    case '.html':
      return 'text/html;charset=utf-8'
    case '.css':
      return 'text/css;charset=utf-8'
    case '.js':
      return 'text/javascript;charset=utf-8'
    case '.json':
      return 'application/json;charset=utf-8'
    default:
      return 'text/plain;charset=utf-8'
  }
}

/** 下载文件名安全化:去掉 Windows/URL 非法字符,空则兜底 app */
function safeName(s: string): string {
  const cleaned = (s || '')
    .replace(/[\\/:*?"<>|\s]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
  return cleaned || 'app'
}

/** 下载全部代码:单文件保留真实扩展名,多文件拼接为 txt */
export function downloadCodeFiles(appName: string, files: { name: string; content: string }[]): void {
  if (!files || files.length === 0) return
  let blob: Blob
  let fileName: string
  if (files.length === 1) {
    const f = files[0]!
    const ext = extOf(f.name)
    blob = new Blob([f.content], { type: mimeOf(f.name) })
    fileName = `${safeName(appName)}${ext || '.txt'}`
  } else {
    const all = files.map((x) => `// ===== ${x.name} =====\n${x.content}`).join('\n\n')
    blob = new Blob([all], { type: 'text/plain;charset=utf-8' })
    fileName = `${safeName(appName)}-代码.txt`
  }
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.click()
  URL.revokeObjectURL(url)
}
