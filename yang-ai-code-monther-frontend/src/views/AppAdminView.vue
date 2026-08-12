<script setup lang="ts">
/**
 * 应用管理页面(/admin/apps,仅管理员)
 *
 * 管理员维护「应用广场」:手动设置/取消精选(priority)、调整精选排序(上移/下移)、删除应用。
 * 对接 /app/admin/list/page、/app/admin/update、/app/admin/delete。
 * 用户部署应用不再自动上广场,由这里决定展示哪些。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  adminDeleteApp,
  adminListApps,
  adminUpdateApp,
  type AppVO,
  type Page,
} from '@/api/generation'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const isAdmin = computed(() => userStore.userInfo?.userRole === 'admin')

/** 查询条件 */
const query = reactive<{
  appName?: string
  pageNum: number
  pageSize: number
}>({
  appName: undefined,
  pageNum: 1,
  pageSize: 20,
})

const records = ref<AppVO[]>([])
const total = ref(0)
const loading = ref(false)
const acting = ref('') // 正在操作的 appId,防连点

async function fetchList() {
  if (!isAdmin.value) return
  loading.value = true
  try {
    const page: Page<AppVO> = await adminListApps({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      appName: query.appName || undefined,
      sortField: 'createTime',
      sortOrder: 'descend',
    })
    records.value = page?.records ?? []
    total.value = Number(page?.totalRow ?? 0)
  } catch {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.appName = undefined
  query.pageNum = 1
  fetchList()
}

/** 设置 / 取消精选(priority = 1 / 0) */
async function setFeatured(app: AppVO, featured: boolean) {
  await updatePriority(app, featured ? 1 : 0, featured ? '已设为广场精选' : '已取消精选')
}

/** 上移 / 下移:调整精选排序(priority +1 / max(0,-1)) */
async function move(app: AppVO, dir: 'up' | 'down') {
  const next = dir === 'up' ? (app.priority ?? 0) + 1 : Math.max(0, (app.priority ?? 0) - 1)
  await updatePriority(app, next, dir === 'up' ? '已上移一位' : '已下移一位')
}

async function updatePriority(app: AppVO, priority: number, okText: string) {
  if (acting.value) return
  acting.value = app.id
  try {
    await adminUpdateApp({ id: app.id, priority })
    message.success(okText)
    fetchList()
  } catch (e) {
    message.error((e as Error).message || '操作失败')
  } finally {
    acting.value = ''
  }
}

function handleDelete(app: AppVO) {
  Modal.confirm({
    title: '删除应用',
    content: `确定删除「${app.appName || '未命名应用'}」吗?其对话记录也会一并删除。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await adminDeleteApp(app.id)
        message.success('已删除')
        fetchList()
      } catch (e) {
        message.error((e as Error).message || '删除失败')
      }
    },
  })
}

onMounted(fetchList)
</script>

<template>
  <div class="admin-page">
    <div class="admin-head">
      <h2 class="admin-title">应用管理</h2>
      <span class="admin-desc">维护「应用广场」:设为精选的应用(优先级 &gt; 0)固定展示,用户部署不自动上广场</span>
    </div>

    <!-- 无权限 -->
    <div v-if="!isAdmin" class="no-auth">
      <div class="no-auth-icon">🔒</div>
      <div class="no-auth-text">只有管理员可以访问应用管理页面</div>
    </div>

    <template v-else>
      <!-- 筛选表单 -->
      <div class="filter-bar">
        <a-input
          v-model:value="query.appName"
          class="filter-name"
          placeholder="应用名称"
          allow-clear
          @press-enter="handleSearch"
        />
        <a-button type="primary" @click="handleSearch">查询</a-button>
        <a-button @click="handleReset">重置</a-button>
      </div>

      <!-- 数据表格 -->
      <a-table
        :columns="[
          { title: '应用名称', dataIndex: 'appName', key: 'appName', ellipsis: true },
          { title: '拥有者', dataIndex: 'userId', key: 'userId', width: 150 },
          { title: '部署状态', dataIndex: 'deployKey', key: 'deployKey', width: 90 },
          { title: '优先级', dataIndex: 'priority', key: 'priority', width: 80 },
          { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
          { title: '操作', key: 'action', width: 220 },
        ]"
        :data-source="records"
        :loading="loading"
        :pagination="{
          current: query.pageNum,
          pageSize: query.pageSize,
          total,
          showTotal: (t: number) => `共 ${t} 个应用`,
        }"
        row-key="id"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'appName'">
            <span class="app-name-cell">{{ record.appName || '未命名应用' }}</span>
          </template>
          <template v-else-if="column.key === 'deployKey'">
            <a-tag :color="record.deployKey ? 'green' : 'default'">
              {{ record.deployKey ? '已部署' : '未部署' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'priority'">
            <span :class="['priority-num', (record.priority ?? 0) > 0 ? 'featured' : '']">
              {{ record.priority ?? 0 }}
            </span>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space :size="4">
              <template v-if="(record.priority ?? 0) > 0">
                <a-button size="small" :loading="acting === record.id" @click="setFeatured(record, false)">取消精选</a-button>
                <a-button size="small" @click="move(record, 'up')">上移</a-button>
                <a-button size="small" @click="move(record, 'down')">下移</a-button>
              </template>
              <a-button v-else size="small" type="primary" ghost :loading="acting === record.id" @click="setFeatured(record, true)">
                设为精选
              </a-button>
              <a-button size="small" danger @click="handleDelete(record)">删除</a-button>
            </a-space>
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

.filter-name {
  width: 200px;
}

.app-name-cell {
  font-weight: 500;
}

.priority-num {
  font-variant-numeric: tabular-nums;
  color: var(--text-3);
}

.priority-num.featured {
  color: var(--primary);
  font-weight: 600;
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
