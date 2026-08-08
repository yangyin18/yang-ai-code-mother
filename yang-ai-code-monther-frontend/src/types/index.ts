/**
 * 全局类型定义
 *
 * 集中存放数据模型,方便前后端数据结构对齐。
 * 这些类型既被 mock 数据使用,将来也会被真实后端接口使用。
 */

/**
 * 生成的代码文件
 */
export interface CodeFile {
  /** 文件名,例如 index.vue */
  name: string
  /** 文件内容 */
  content: string
}

/**
 * 预览界面配置
 *
 * 结果页会根据这份「配置」渲染出一个可交互的模拟界面,
 * 用来模拟 AI 生成的完整应用长什么样。
 */
export interface PreviewConfig {
  /** 界面顶部标题 */
  title: string
  /** 统计卡片(一排数字卡片) */
  stats: { label: string; value: string }[]
  /** 操作按钮 */
  actions: { label: string; key: string }[]
  /** 记录列表 */
  records: { date: string; content: string }[]
}

/**
 * AI 生成出来的应用
 */
export interface GeneratedApp {
  /** 应用唯一 ID */
  id: string
  /** 应用名称 */
  name: string
  /** 一句话描述 */
  description: string
  /** 图标(用 emoji 简单表示) */
  icon: string
  /** 匹配关键词:识别用户需求时用的关键词 */
  keywords: string[]
  /** 界面预览配置 */
  preview: PreviewConfig
  /** 生成的代码文件列表 */
  files: CodeFile[]
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
 * 登录用户信息
 */
export interface UserInfo {
  /** 用户 ID */
  id: string
  /** 用户名 */
  username: string
  /** 头像(暂用首字母渲染,预留 URL 字段) */
  avatar?: string
}
