<script setup lang="ts">
/**
 * 我的对话列表页(/conversations)
 *
 * 展示当前用户所有应用的会话摘要(每个应用最新一条消息 + 消息数),
 * 点击卡片进入对应应用的对话页 /chat/:appId。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { listMyConversations, type ChatConversation } from '@/api/chat'
import { deleteApp } from '@/api/generation'
import { useUserStore } from '@/stores/user'
import AppCover from '@/components/AppCover.vue'

const router = useRouter()
const userStore = useUserStore()

const conversations = ref<ChatConversation[]>([])
const loading = ref(false)

async function load() {
  if (!userStore.isLoggedIn) return
  loading.value = true
  try {
    conversations.value = await listMyConversations()
  } catch {
    conversations.value = []
    message.error('加载会话列表失败,请重试')
  } finally {
    loading.value = false
  }
}

onMounted(load)

function goChat(conv: ChatConversation) {
  router.push(`/chat/${conv.appId}`)
}

/** 删除应用:二次确认后调用后端,成功后从会话列表移除(与首页「我的应用」删除同一接口) */
function handleDelete(conv: ChatConversation) {
  Modal.confirm({
    title: '删除应用',
    content: `确定删除「${conv.appName || '未命名应用'}」吗?代码文件与对话记录都会一并删除,无法恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await deleteApp(conv.appId)
        message.success('已删除')
        conversations.value = conversations.value.filter((c) => c.appId !== conv.appId)
      } catch (e) {
        message.error((e as Error).message || '删除失败')
      }
    },
  })
}

/** 最新消息类型 → 标签文案与颜色 */
function typeInfo(t?: string): { text: string; color: string } {
  if (t === 'user') return { text: '我', color: 'blue' }
  if (t === 'error') return { text: '错误', color: 'orange' }
  return { text: 'AI', color: 'green' }
}

/** 时间格式化(相对友好) */
function fmtTime(t?: string): string {
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
</script>

<template>
  <div class="conversations-page">
    <div class="page-head term-shell">
      <div class="term-bar">
        <span class="term-dot red" />
        <span class="term-dot yellow" />
        <span class="term-dot green" />
        <span class="term-title mono">user@matrix:~/apps $ ls</span>
      </div>
      <div class="page-head-body">
        <h2 class="page-title mono"><span class="prompt-sym">>_</span> 我的对话</h2>
        <span class="page-desc mono">// 每个应用一条会话,AI 会记住你的应用与最近对话</span>
      </div>
    </div>

    <!-- 未登录 -->
    <div v-if="!userStore.isLoggedIn" class="empty-state">
      <div class="empty-icon">🔒</div>
      <div class="empty-text">登录后即可查看你的对话记录</div>
      <a-button type="primary" @click="router.push('/login')">去登录</a-button>
    </div>

    <!-- 加载中 -->
    <div v-else-if="loading" class="empty-state">加载中...</div>

    <!-- 空态 -->
    <div v-else-if="conversations.length === 0" class="empty-state">
      <div class="empty-icon">💬</div>
      <div class="empty-text">还没有对话记录,去首页生成一个应用,开始和 AI 聊聊吧</div>
      <a-button type="primary" @click="router.push('/')">去首页生成</a-button>
    </div>

    <!-- 会话列表 -->
    <div v-else class="conv-list">
      <div
        v-for="conv in conversations"
        :key="conv.appId"
        class="conv-card"
        @click="goChat(conv)"
      >
        <!-- 右上角删除:默认半透明,悬浮卡片才显现,误触成本低 -->
        <button
          type="button"
          class="conv-delete"
          title="删除应用"
          @click.stop="handleDelete(conv)"
        >✕</button>
        <div class="conv-cover">
          <AppCover :name="conv.appName" :cover="conv.cover" :live="!!conv.deployUrl" />
        </div>
        <div class="conv-main">
          <div class="conv-title">
            <span class="conv-name">{{ conv.appName || '未命名应用' }}</span>
            <span class="conv-count">{{ conv.messageCount ?? 0 }} 条消息</span>
          </div>
          <div class="conv-preview">
            <a-tag :color="typeInfo(conv.latestMessageType).color" class="conv-tag">
              {{ typeInfo(conv.latestMessageType).text }}
            </a-tag>
            <span class="conv-text">{{ conv.latestMessage || '还没有消息' }}</span>
          </div>
          <div class="conv-meta">
            <span>{{ conv.latestTime ? fmtTime(conv.latestTime) : '暂无对话' }}</span>
            <span class="conv-action">进入对话 ›</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.conversations-page {
  max-width: 760px;
  margin: 0 auto;
  animation: fade-up 0.5s ease both;
}

/* 页面头部 = 终端窗口 */
.page-head {
  margin-bottom: 20px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
  box-shadow: var(--shadow);
}

.page-head-body {
  padding: 18px 20px;
}

.prompt-sym {
  color: var(--primary);
  font-weight: 700;
  text-shadow: 0 0 8px rgba(0, 255, 157, 0.6);
}

.page-title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.3px;
}

.page-desc {
  color: var(--text-3);
  font-size: 13px;
}

.empty-state {
  padding: 60px 20px;
  text-align: center;
  color: var(--text-3);
  font-size: 14px;
  background: rgba(0, 0, 0, 0.2);
  border: 1px dashed var(--border);
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}

.empty-icon {
  font-size: 40px;
}

.conv-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 会话卡片 = 终端行 */
.conv-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 16px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 12px;
  cursor: pointer;
  transition: transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
}

/* 右上角删除:小尺寸、半透明、悬浮卡片才明显,弱化存在感避免误触 */
.conv-delete {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 2;
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.4);
  color: rgba(255, 255, 255, 0.45);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  opacity: 0.45;
  transition: opacity 0.2s ease, color 0.2s ease, background 0.2s ease, border-color 0.2s ease;
}

.conv-card:hover .conv-delete,
.conv-delete:focus-visible {
  opacity: 1;
  color: rgba(255, 255, 255, 0.75);
}

.conv-delete:hover {
  color: #fca5a5;
  border-color: rgba(244, 63, 94, 0.55);
  background: rgba(244, 63, 94, 0.15);
}

.conv-card:hover {
  transform: translateY(-2px);
  border-color: rgba(0, 255, 157, 0.5);
  box-shadow: var(--glow);
}

.conv-cover {
  position: relative;
  width: 64px;
  height: 64px;
  border-radius: 12px;
  overflow: hidden;
  flex-shrink: 0;
  background: #060a10;
}

.conv-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.conv-main {
  flex: 1;
  min-width: 0;
  /* 给右上角删除按钮让位:内容区右缘缩进,避免「N 条消息」角标被 ✕ 按钮盖住 */
  padding-right: 30px;
}

.conv-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 4px;
}

.conv-name {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

.conv-name::before {
  content: '>_ ';
  color: var(--primary);
  font-weight: 700;
}

.conv-count {
  font-size: 11px;
  color: var(--success);
  flex-shrink: 0;
  border: 1px solid rgba(0, 255, 157, 0.3);
  background: rgba(0, 255, 157, 0.06);
  padding: 1px 8px;
  border-radius: 999px;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

.conv-preview {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.conv-tag {
  margin: 0;
  flex-shrink: 0;
}

.conv-text {
  font-size: 13px;
  color: var(--text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conv-meta {
  font-size: 12px;
  color: var(--text-3);
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

.conv-action {
  color: var(--primary);
}

@keyframes fade-up {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
