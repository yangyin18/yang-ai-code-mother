/**
 * 全局类型定义
 *
 * 集中存放数据模型,与后端接口数据结构对齐。
 * 前端已接入真实后端(注册/登录 → 创建应用 → AI 生成 → nginx 部署)。
 */

/**
 * 生成的代码文件(用于结果页代码区展示)
 */
export interface CodeFile {
  /** 文件名,例如 index.html / style.css / script.js */
  name: string
  /** 文件内容 */
  content: string
}

/**
 * 项目文件(带路径,通用「查看代码」渲染用)。
 * 快速开发是 index.html 等扁平文件,Vue 深度开发则是 src/App.vue 等嵌套路径。
 */
export interface ProjectFile {
  /** 项目内相对路径,如 index.html / src/App.vue */
  path: string
  /** 文件内容 */
  content: string
}

/**
 * AI 生成并部署后的应用(对应后端 CodeGenResult + AppVO 的合并)
 */
export interface GeneratedResult {
  /** 应用 ID(后端 App.id,雪花 ID 以字符串传输避免精度丢失) */
  appId: string
  /** 应用名称 */
  name: string
  /** 功能描述 */
  description: string
  /** HTML 代码(html 模式为完整页面) */
  htmlCode: string
  /** CSS 代码(multi_file 模式才有) */
  cssCode: string
  /** JS 代码(multi_file 模式才有) */
  jsCode: string
  /** 生成的文件名列表 */
  fileNames: string[]
  /** 已部署到 nginx 的访问地址,如 http://localhost/apps/a1b2c3d4/ */
  deployUrl?: string
}

/**
 * AI 生成代码的原始结果(不含应用名称/部署地址,由 store 合并 AppVO 得到完整结果)
 */
export interface CodeGenPayload {
  /** 应用 ID */
  appId: string
  /** 功能描述 */
  description: string
  /** HTML 代码 */
  htmlCode: string
  /** CSS 代码(multi_file 模式才有) */
  cssCode: string
  /** JS 代码(multi_file 模式才有) */
  jsCode: string
  /** 生成的文件名列表 */
  fileNames: string[]
}

/**
 * 生成过程中的一个步骤
 */
export interface GenerateStep {
  /** 步骤唯一标识 */
  key: string
  /** 步骤标题 */
  title: string
  /** 步骤说明 */
  desc: string
}

/**
 * 登录用户信息(对应后端 LoginUserVO,已脱敏)
 */
export interface UserInfo {
  /** 用户 ID */
  id: string
  /** 用户名(账号) */
  username: string
  /** 头像地址(可选) */
  avatar?: string
  /** 用户角色(user / admin),用于管理员入口展示与页面鉴权 */
  userRole?: string
}
