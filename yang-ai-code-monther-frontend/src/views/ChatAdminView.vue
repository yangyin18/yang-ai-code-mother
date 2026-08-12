<script setup lang="ts">
/**
 * 对话管理页面(/admin/chat,仅管理员)
 *
 * 后台管理通用风格:筛选表单 + 表格 + 分页,对接 /chat/admin/list/page。
 * 默认按创建时间倒序,支持按消息类型 / 应用 id / 用户 id 过滤。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { adminListChatHistory, type ChatMessage } from '@/api/chat'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

/** 查询条件 */
const query = reactive<{
  messageType?: string
  appId?: string
  userId?: string
  pageNum: number
  pageSize: number
  sortField?: string
  sortOrder?: string
}>({
  messageType: undefined,
  appId: undefined,
  userId: undefined,
  pageNum: 1,
  pageSize: 20,
  sortField: 'createTime',
  sortOrder: 'descend',
})

const records = ref<ChatMessage[]>([])
const total = ref(0)
const loading = ref(false)
/** 登录态从 localStorage 恢复后自动对齐,用 computed 而非 ref */
const isAdmin = computed(() => userStore.userInfo?.userRole === 'admin')

/** 消息类型 → 标签颜色 */
const typeColor: Record<string, string> = {
  user: 'blue',
  ai: 'green',
  error: 'orange',
}

/** 拉取一页数据 */
async function fetchList() {
  if (!isAdmin.value) return
  loading.value = true
  try {
    const page = await adminListChatHistory({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      appId: query.appId || undefined,
      userId: query.userId || undefined,
      messageType: query.messageType || undefined,
      sortField: query.sortField,
      sortOrder: query.sortOrder,
    })
    records.value = page.records ?? []
    // 后端全局 Long→String 序列化,分页字段是字符串("228"),转成数字给 antd 分页用
    total.value = Number(page.totalRow ?? 0)
  } catch {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 筛选/重置 */
function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.messageType = undefined
  query.appId = undefined
  query.userId = undefined
  query.pageNum = 1
  fetchList()
}

/** 表格排序变化 → 重新查询 */
function handleTableChange(pag: { current?: number; pageSize?: number }, _filter: unknown, sorter: unknown) {
  const s = sorter as { field?: string; order?: string } | undefined
  query.pageNum = pag.current ?? 1
  query.pageSize = pag.pageSize ?? query.pageSize
  query.sortField = s?.field
  query.sortOrder = s?.order
  fetchList()
}

onMounted(fetchList)
</script>

<template>
  <div class="admin-page">
    <div class="admin-head">
      <h2 class="admin-title">对话管理</h2>
      <span class="admin-desc">查看全部应用的对话内容,按时间倒序监管</span>
    </div>

    <!-- 无权限 -->
    <div v-if="!isAdmin" class="no-auth">
      <div class="no-auth-icon">🔒</div>
      <div class="no-auth-text">只有管理员可以访问对话管理页面</div>
    </div>

    <template v-else>
      <!-- 筛选表单 -->
      <div class="filter-bar">
        <a-select
          v-model:value="query.messageType"
          class="filter-type"
          placeholder="消息类型"
          allow-clear
          @change="handleSearch"
        >
          <a-select-option value="user">用户消息</a-select-option>
          <a-select-option value="ai">AI 消息</a-select-option>
          <a-select-option value="error">错误消息</a-select-option>
        </a-select>
        <a-input
          v-model:value="query.appId"
          class="filter-id"
          placeholder="应用 id"
          allow-clear
          @press-enter="handleSearch"
        />
        <a-input
          v-model:value="query.userId"
          class="filter-id"
          placeholder="用户 id"
          allow-clear
          @press-enter="handleSearch"
        />
        <a-button type="primary" @click="handleSearch">查询</a-button>
        <a-button @click="handleReset">重置</a-button>
      </div>

      <!-- 数据表格 -->
      <a-table
        :columns="[
          { title: '类型', dataIndex: 'messageType', key: 'messageType', width: 90 },
          { title: '消息内容', dataIndex: 'message', key: 'message', ellipsis: true },
          { title: '应用 id', dataIndex: 'appId', key: 'appId', width: 150 },
          { title: '用户 id', dataIndex: 'userId', key: 'userId', width: 150 },
          { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170, sorter: true, defaultSortOrder: 'descend' },
        ]"
        :data-source="records"
        :loading="loading"
        :pagination="{
          current: query.pageNum,
          pageSize: query.pageSize,
          total,
          showSizeChanger: true,
          showTotal: (t: number) => `共 ${t} 条`,
        }"
        row-key="id"
        size="middle"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'messageType'">
            <a-tag :color="typeColor[record.messageType] ?? 'default'">
              {{ record.messageType === 'user' ? '用户' : record.messageType === 'ai' ? 'AI' : '错误' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'message'">
            <a-tooltip :title="record.message">
              <span class="msg-cell">{{ record.message }}</span>
            </a-tooltip>
          </template>
        </template>
      </a-table>
    </template>
  </div>
</template>

<style scoped>
.admin-page {
  animation: fade-up 0.5s ease both;
}

.admin-head {
  margin-bottom: 20px;
}

.admin-title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 700;
}

.admin-desc {
  color: var(--text-3);
  font-size: 13px;
}

.no-auth {
  text-align: center;
  padding: 80px 20px;
  color: var(--text-3);
}

.no-auth-icon {
  font-size: 40px;
  margin-bottom: 12px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.filter-type {
  width: 130px;
}

.filter-id {
  width: 180px;
}

.msg-cell {
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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
