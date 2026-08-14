/**
 * 对话页截图脚本：用已安装的 Headless Chrome(puppeteer-core，不下载浏览器内核)
 * 截取应用对话页 /chat/:appId，作为「我的应用」封面。
 *
 * 用法：
 *   node screenshot.js <url> <cookie> <outputPath> [settleMs] [chromePath]
 *     <url>        要截图的对话页地址，如 http://localhost:5173/chat/3
 *     <cookie>     会话 cookie，格式 "name=value"，通常是 "JSESSIONID=xxx"
 *     <outputPath> 截图 PNG 输出路径
 *     <settleMs>   页面渲染完成后的静置时长(ms)，等右栏应用预览(srcdoc/iframe)渲染，默认 3500
 *     <chromePath> Chrome 可执行文件路径；缺省时自动探测常见位置
 *
 * 属主用户信息 JSON 经环境变量 YANG_AI_COVER_USER 传入（不放在命令行参数里：
 * Windows 上 Java ProcessBuilder 构造命令行时会破坏含双引号的参数，导致 JSON 解析失败）。
 * 兼容旧用法：也接受第 3 个位置参数作为 userJson。
 *
 * 登录态说明：前端把登录用户缓存在 localStorage(yang-ai-user-v2)，仅靠会话 cookie
 * 不足以让 /chat 页放行，因此脚本先在同一 origin 种好 localStorage，再跳转对话页；
 * 页面启动时 store 仍会用 cookie 调 /user/get/login 刷新用户信息，两者都具备才一致。
 *
 * 退出码：0 = 截图成功并校验通过；1 = 失败(原因打印到 stderr)。
 */
import puppeteer from 'puppeteer-core'
import { existsSync, statSync } from 'node:fs'
import path from 'node:path'

// <url> <cookie> <userJson> <outputPath> <settleMs> <chromePath>  —— 兼容旧 6 参调用
// <url> <cookie> <outputPath> <settleMs> <chromePath>             —— 新 5 参调用，userJson 走环境变量
const rawArgs = process.argv.slice(2)
const url = rawArgs[0]
const cookieArg = rawArgs[1]
let userJsonArg = rawArgs[2]
let outputPath = rawArgs[3]
let settleMsArg = rawArgs[4]
let chromePathArg = rawArgs[5]
// 新 5 参调用 <url> <cookie> <outputPath> <settleMs> <chromePath>：第 3 参以 .png 结尾即识别，
// userJson 从环境变量 YANG_AI_COVER_USER 读取；旧 6 参调用（第 3 参是 userJson）也兼容。
if (userJsonArg && /\.png$/i.test(userJsonArg)) {
  outputPath = userJsonArg
  userJsonArg = process.env.YANG_AI_COVER_USER || ''
  settleMsArg = rawArgs[4]
  chromePathArg = rawArgs[5]
} else {
  userJsonArg = userJsonArg || process.env.YANG_AI_COVER_USER || ''
}

if (!url || !cookieArg || !userJsonArg || !outputPath) {
  console.error('用法: node screenshot.js <url> <cookie> <userJson|outputPath> <outputPath> [settleMs] [chromePath]（userJson 也可经环境变量 YANG_AI_COVER_USER 传入）')
  process.exit(1)
}

const settleMs = settleMsArg ? Number(settleMsArg) : 3500
const executablePath = resolveChrome(chromePathArg)
if (!executablePath) {
  console.error('未找到 Chrome/Edge 可执行文件，无法截图')
  process.exit(1)
}

// 前端 user store 持久化的两个 localStorage key(与 src/stores/user.ts 保持一致)
const LOGIN_KEY = 'yang-ai-logged-in-v2'
const USER_KEY = 'yang-ai-user-v2'

// 解析 "name=value" 会话 cookie
const eq = cookieArg.indexOf('=')
const cookieName = eq > 0 ? cookieArg.slice(0, eq) : cookieArg
const cookieValue = eq > 0 ? cookieArg.slice(eq + 1) : ''
let cookieHost
try {
  cookieHost = new URL(url).host
} catch {
  cookieHost = 'localhost'
}

async function main() {
  const browser = await puppeteer.launch({
    executablePath,
    headless: true,
    args: ['--disable-gpu', '--hide-scrollbars', '--no-sandbox', '--disable-setuid-sandbox'],
  })
  try {
    const page = await browser.newPage()
    await page.setViewport({ width: 1280, height: 800 })
    if (cookieValue) {
      await page.setCookie({ name: cookieName, value: cookieValue, domain: cookieHost, path: '/' })
    }
    // 第一步：先加载同源页面，建立 origin，再种 localStorage（不同 origin 的 localStorage 互不相通）。
    // 首屏 user store 读不到 localStorage 会被后置到 /login，但同源，不影响后续跳转。
    const origin = new URL(url).origin
    await page.goto(origin + '/login', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.evaluate(
      ({ loginKey, userKey, userJson }) => {
        localStorage.setItem(loginKey, '1')
        localStorage.setItem(userKey, JSON.stringify(JSON.parse(userJson)))
      },
      { loginKey: LOGIN_KEY, userKey: USER_KEY, userJson: userJsonArg },
    )
    // 第二步：带登录态跳转对话页。domcontentloaded 后等对话页结构出现；
    // 不用 networkidle(对话页可能有长连接/心跳，等不到空闲)
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector('.chat-page', { timeout: 15000 })
    // 等对话历史加载完成：有消息时出现 .msg-list，空/无权限时出现 .state(含 .waiting 之外的初始态)
    await page.waitForSelector('.msg-list, .state', { timeout: 15000 })
    // 静置：等右栏应用预览(srcdoc 实时渲染 / 已部署 iframe)真正画出来
    await new Promise((r) => setTimeout(r, settleMs))
    await page.screenshot({ path: outputPath, type: 'png' })
    // 兜底校验：文件存在且大于 3KB(空白页/纯错误页 PNG 极小)，过小视为失败
    const out = path.resolve(outputPath)
    if (!existsSync(out) || statSync(out).size < 3072) {
      console.error(`封面截图过小或为空，已放弃：${out}(${existsSync(out) ? statSync(out).size : 0} bytes)`)
      process.exit(1)
    }
    console.log(`封面截图完成：${out}(${statSync(out).size} bytes)`)
    process.exit(0)
  } finally {
    await browser.close()
  }
}

/** 解析 Chrome 可执行文件路径：优先显式传入，否则探测常见位置 */
function resolveChrome(explicit) {
  if (explicit) return explicit
  const common = [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    process.env.LOCALAPPDATA ? process.env.LOCALAPPDATA + '\\Google\\Chrome\\Application\\chrome.exe' : '',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
  ]
  for (const c of common) {
    if (c && existsSync(c)) return c
  }
  return null
}

main().catch((e) => {
  console.error('截图失败：', e && e.message ? e.message : String(e))
  process.exit(1)
})
