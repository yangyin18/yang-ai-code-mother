/**
 * Mock 数据层
 *
 * 在后端 AI 生成接口做好之前,先用一份「模板库」模拟 AI 生成效果:
 *   - 根据用户输入中的关键词匹配模板
 *   - 模拟网络延迟后返回对应的应用
 *
 * 每个模板都包含「界面预览配置」和「生成的代码」,
 * 让前端整个闭环(输入 → 生成 → 预览)可以先跑起来。
 */
import type { CodeFile, GeneratedApp, GenerateStep, UserInfo } from '@/types'

/** 生成过程的固定步骤(生成页按这个顺序逐条展示) */
export const GENERATE_STEPS: GenerateStep[] = [
  { key: 'understand', title: '理解需求', desc: '分析你的需求描述,提取核心功能点' },
  { key: 'design', title: '设计架构', desc: '设计界面结构、数据结构与交互逻辑' },
  { key: 'code', title: '生成代码', desc: '编写页面组件与业务逻辑代码' },
  { key: 'done', title: '组装完成', desc: '打包校验,准备预览你的应用' },
]

/** 构造一个代码文件 */
function file(name: string, content: string): CodeFile {
  return { name, content }
}

/**
 * 模板库
 */
const TEMPLATES: GeneratedApp[] = [
  // ============ 模板一:打卡助手 ============
  {
    id: 'tpl-punch',
    name: '打卡助手',
    description: '记录每日打卡,追踪连续坚持天数',
    icon: '✅',
    keywords: ['打卡', '签到', '坚持', '习惯'],
    preview: {
      title: '今日打卡',
      stats: [
        { label: '连续打卡', value: '12 天' },
        { label: '本月打卡', value: '26 次' },
      ],
      actions: [{ label: '立即打卡', key: 'punch' }],
      records: [
        { date: '今天 08:00', content: '打卡成功,连续第 12 天' },
        { date: '昨天 08:15', content: '打卡成功,连续第 11 天' },
        { date: '前天 07:50', content: '打卡成功,连续第 10 天' },
      ],
    },
    files: [
      file(
        'index.vue',
        `<script setup lang="ts">
import { ref } from 'vue'
import { message } from 'ant-design-vue'

// 打卡天数状态
const days = ref(12)
const times = ref(26)
const records = ref([
  { date: '今天 08:00', content: '打卡成功' },
  { date: '昨天 08:15', content: '打卡成功' },
])

// 点击打卡按钮
function punch() {
  days.value += 1
  times.value += 1
  records.value.unshift({
    date: '刚刚',
    content: '打卡成功,连续第 ' + days.value + ' 天',
  })
  message.success('打卡成功!')
}
</script>

<template>
  <div class="punch-card">
    <h2>✅ 今日打卡</h2>
    <div class="stats">
      <div><span>连续</span><strong>{{ days }}</strong> 天</div>
      <div><span>本月</span><strong>{{ times }}</strong> 次</div>
    </div>
    <button @click="punch">立即打卡</button>
    <ul>
      <li v-for="r in records" :key="r.date">{{ r.date }} {{ r.content }}</li>
    </ul>
  </div>
</template>

<style scoped>
.stats { display: flex; gap: 24px; margin: 12px 0; }
.stats strong { font-size: 24px; margin: 0 4px; }
button { padding: 8px 24px; border-radius: 6px; border: none; background: #1677ff; color: #fff; cursor: pointer; }
</style>`,
      ),
    ],
  },

  // ============ 模板二:记账本 ============
  {
    id: 'tpl-book',
    name: '记账本',
    description: '随手记一笔,看清钱花到哪了',
    icon: '💰',
    keywords: ['记账', '账本', '收支', '财务', '账单'],
    preview: {
      title: '本月账单',
      stats: [
        { label: '本月支出', value: '¥ 2,368' },
        { label: '本月收入', value: '¥ 12,000' },
      ],
      actions: [{ label: '记一笔', key: 'add' }],
      records: [
        { date: '08-05', content: '餐饮 ¥ 45' },
        { date: '08-05', content: '交通 ¥ 12' },
        { date: '08-04', content: '购物 ¥ 268' },
        { date: '08-03', content: '工资 ¥ 12,000' },
      ],
    },
    files: [
      file(
        'index.vue',
        `<script setup lang="ts">
import { ref } from 'vue'
import { message } from 'ant-design-vue'

const total = ref(2368)
const records = ref([
  { date: '08-05', type: '支出', amount: 45, name: '餐饮' },
  { date: '08-05', type: '支出', amount: 12, name: '交通' },
  { date: '08-03', type: '收入', amount: 12000, name: '工资' },
])

function addRecord() {
  message.info('演示:这里会弹出一个添加收支的表单')
}
</script>

<template>
  <div class="book">
    <h2>💰 本月账单</h2>
    <div class="stats">
      <div class="expense"><span>支出</span><strong>¥ {{ total }}</strong></div>
    </div>
    <button @click="addRecord">记一笔</button>
    <ul>
      <li v-for="r in records" :key="r.date + r.name">
        {{ r.date }} {{ r.name }}
        <span :class="r.type === '收入' ? 'income' : ''">
          {{ r.type === '收入' ? '+' : '-' }}¥{{ r.amount }}
        </span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.expense strong { color: #f5222d; }
.income { color: #52c41a; }
</style>`,
      ),
    ],
  },

  // ============ 模板三:待办清单 ============
  {
    id: 'tpl-todo',
    name: '待办清单',
    description: '把要做的事列出来,逐项搞定',
    icon: '📋',
    keywords: ['待办', '清单', '任务', 'todo', '计划'],
    preview: {
      title: '我的待办',
      stats: [
        { label: '待完成', value: '5' },
        { label: '已完成', value: '12' },
      ],
      actions: [{ label: '新增待办', key: 'add' }],
      records: [
        { date: '今天', content: '整理周报' },
        { date: '今天', content: '阅读源码笔记' },
        { date: '明天', content: '预约体检' },
        { date: '本周', content: '复习 Vue 响应式原理' },
      ],
    },
    files: [
      file(
        'index.vue',
        `<script setup lang="ts">
import { ref } from 'vue'

const todos = ref([
  { text: '整理周报', done: false },
  { text: '阅读源码笔记', done: false },
  { text: '预约体检', done: true },
])

const input = ref('')

function addTodo() {
  if (!input.value.trim()) return
  todos.value.unshift({ text: input.value.trim(), done: false })
  input.value = ''
}
</script>

<template>
  <div class="todo">
    <h2>📋 我的待办</h2>
    <div class="add">
      <input v-model="input" placeholder="输入待办内容,回车添加" @keyup.enter="addTodo" />
    </div>
    <ul>
      <li v-for="(t, i) in todos" :key="i" :class="{ done: t.done }">
        <input type="checkbox" v-model="t.done" />
        <span>{{ t.text }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.done span { text-decoration: line-through; color: #999; }
input[type="text"] { width: 100%; padding: 8px; }
</style>`,
      ),
    ],
  },
]

/**
 * 模拟 AI 生成应用
 *
 * @param requirement 用户输入的需求描述
 * @returns 匹配到的模板应用
 */
export function mockGenerate(requirement: string): Promise<GeneratedApp> {
  return new Promise((resolve) => {
    // 模拟网络延迟
    setTimeout(() => {
      // 用关键词做简单的「意图识别」:命中最多的模板胜出
      // TEMPLATES[0] 在严格索引模式下可能为 undefined,用非空断言确保取到模板
      let best: GeneratedApp = TEMPLATES[0]!
      let bestScore = 0
      for (const tpl of TEMPLATES) {
        const score = tpl.keywords.reduce(
          (sum, kw) => sum + (requirement.includes(kw) ? 1 : 0),
          0,
        )
        if (score > bestScore) {
          best = tpl
          bestScore = score
        }
      }
      // 为每次生成生成新的 id,模拟全新生成的应用
      resolve({ ...best, id: `app-${Date.now()}` })
    }, 500)
  })
}

/* ==================== 用户 mock(模拟登录/注册) ==================== */

/** localStorage 里存放用户表的 key(仅用于前端演示) */
const USERS_KEY = 'yang-ai-users'

/** 读取本地用户表:{ 用户名 -> 密码 } */
function loadUsers(): Record<string, string> {
  try {
    return JSON.parse(localStorage.getItem(USERS_KEY) ?? '{}')
  } catch {
    return {}
  }
}

/** 模拟注册:用户名唯一校验,成功后写入本地用户表 */
export function mockRegister(username: string, password: string): Promise<UserInfo> {
  return new Promise((resolve, reject) => {
    setTimeout(() => {
      const users = loadUsers()
      if (users[username]) {
        reject(new Error('用户名已存在'))
        return
      }
      users[username] = password
      localStorage.setItem(USERS_KEY, JSON.stringify(users))
      resolve({ id: `u-${Date.now()}`, username })
    }, 400)
  })
}

/** 模拟登录:校验用户名密码,成功返回用户信息 */
export function mockLogin(username: string, password: string): Promise<UserInfo> {
  return new Promise((resolve, reject) => {
    setTimeout(() => {
      const users = loadUsers()
      if (users[username] === password) {
        resolve({ id: `u-${Date.now()}`, username })
      } else {
        reject(new Error('用户名或密码错误'))
      }
    }, 400)
  })
}
