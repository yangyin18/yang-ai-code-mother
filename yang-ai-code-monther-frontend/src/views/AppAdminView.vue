<script setup lang="ts">
/**
 * 应用管理页面(/admin/apps,仅管理员)
 *
 * 管理员快捷维护「应用广场」:增(新建应用卡片)/ 查(名称筛选 + 只看精选)/
 * 改(编辑名称/需求描述/封面/优先级,直接填数字)/ 删(删除应用)。
 * 对接 /app/admin/create、/app/admin/list/page、/app/admin/update、/app/admin/delete。
 * 用户部署应用不再自动上广场,由这里决定展示哪些。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  adminCreateApp,
  adminDeleteApp,
  adminListApps,
  adminUpdateApp,
  type AppVO,
  type Page,
} from '@/api/generation'
import { useUserStore } from '@/stores/user'
import AppCover from '@/components/AppCover.vue'

const userStore = useUserStore()

const isAdmin = computed(() => userStore.userInfo?.userRole === 'admin')

/** 查询条件 */
const query = reactive<{
  appName?: string
  featuredOnly: boolean
  pageNum: number
  pageSize: number
}>({
  appName: undefined,
  featuredOnly: false,
  pageNum: 1,
  pageSize: 20,
})

const records = ref<AppVO[]>([])
const total = ref(0)
const loading = ref(false)
const acting = ref('') // 正在操作的 appId,防连点

/** 生成类型选项(与后端 CodeGenTypeEnum 对应) */
const CODE_GEN_TYPES = [
  { value: 'html', label: '原生 HTML' },
  { value: 'multi_file', label: '原生多文件' },
  { value: 'vue', label: 'Vue 项目' },
]

async function fetchList() {
  if (!isAdmin.value) return
  loading.value = true
  try {
    const page: Page<AppVO> = await adminListApps({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      appName: query.appName || undefined,
      featuredOnly: query.featuredOnly || undefined,
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
  query.featuredOnly = false
  query.pageNum = 1
  fetchList()
}

// ==================== 新增应用 ====================

const createOpen = ref(false)
const creating = ref(false)
const createForm = reactive({
  appName: '',
  initPrompt: '',
  codeGenType: 'html',
  priority: 5,
})

function openCreate() {
  createForm.appName = ''
  createForm.initPrompt = ''
  createForm.codeGenType = 'html'
  createForm.priority = 5
  createOpen.value = true
}

async function submitCreate() {
  if (!createForm.appName.trim()) {
    message.warning('请填写应用名称')
    return
  }
  if (!createForm.initPrompt.trim()) {
    message.warning('请填写需求描述')
    return
  }
  creating.value = true
  try {
    await adminCreateApp({
      appName: createForm.appName.trim(),
      initPrompt: createForm.initPrompt.trim(),
      codeGenType: createForm.codeGenType,
      priority: createForm.priority ?? 0,
    })
    message.success('应用已创建' + ((createForm.priority ?? 0) > 0 ? '并加入应用广场' : ''))
    createOpen.value = false
    fetchList()
  } catch (e) {
    message.error((e as Error).message || '创建失败')
  } finally {
    creating.value = false
  }
}

// ==================== 编辑应用 ====================

const editOpen = ref(false)
const editing = ref(false)
const editForm = reactive({
  id: '',
  appName: '',
  initPrompt: '',
  cover: '',
  priority: 0,
})

function openEdit(app: AppVO) {
  editForm.id = app.id
  editForm.appName = app.appName || ''
  editForm.initPrompt = app.initPrompt || ''
  editForm.cover = app.cover || ''
  editForm.priority = app.priority ?? 0
  editOpen.value = true
}

async function submitEdit() {
  if (!editForm.appName.trim()) {
    message.warning('请填写应用名称')
    return
  }
  if (!editForm.initPrompt.trim()) {
    message.warning('请填写需求描述')
    return
  }
  editing.value = true
  try {
    await adminUpdateApp({
      id: editForm.id,
      appName: editForm.appName.trim(),
      initPrompt: editForm.initPrompt.trim(),
      cover: editForm.cover.trim() || undefined,
      priority: editForm.priority ?? 0,
    })
    message.success('已保存')
    editOpen.value = false
    fetchList()
  } catch (e) {
    message.error((e as Error).message || '保存失败')
  } finally {
    editing.value = false
  }
}

// ==================== 精选 / 排序 / 删除 ====================

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
        <span class="filter-switch">
          <a-switch v-model:checked="query.featuredOnly" @change="handleSearch" />
          <span class="filter-switch-label">只看精选</span>
        </span>
        <a-button class="create-btn" type="primary" ghost @click="openCreate">➕ 新增应用</a-button>
      </div>

      <!-- 数据表格 -->
      <a-table
        :columns="[
          { title: '封面', dataIndex: 'cover', key: 'cover', width: 132 },
          { title: '应用名称', dataIndex: 'appName', key: 'appName', ellipsis: true },
          { title: '拥有者', dataIndex: 'ownerName', key: 'ownerName', width: 140, ellipsis: true },
          { title: '部署状态', dataIndex: 'deployKey', key: 'deployKey', width: 90 },
          { title: '优先级', dataIndex: 'priority', key: 'priority', width: 80 },
          { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
          { title: '操作', key: 'action', width: 280 },
        ]"
        :data-source="records"
        :loading="loading"
        :pagination="{
          current: query.pageNum,
          pageSize: query.pageSize,
          total,
          showTotal: (t: number) => `共 ${t} 个应用`,
          onChange: (page: number) => {
            query.pageNum = page
            fetchList()
          },
        }"
        row-key="id"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'cover'">
            <div class="cover-thumb">
              <AppCover :name="record.appName" :cover="record.cover" :live="!!record.deployUrl" />
            </div>
          </template>
          <template v-else-if="column.key === 'appName'">
            <span class="app-name-cell">{{ record.appName || '未命名应用' }}</span>
          </template>
          <template v-else-if="column.key === 'ownerName'">
            <span>{{ record.ownerName || record.userId || '—' }}</span>
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
              <a-button size="small" @click="openEdit(record)">编辑</a-button>
              <a-button size="small" danger @click="handleDelete(record)">删除</a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </template>

    <!-- 新增应用弹窗 -->
    <a-modal
      v-model:open="createOpen"
      title="新增应用卡片"
      :confirm-loading="creating"
      :mask-closable="false"
      ok-text="创建"
      cancel-text="取消"
      @ok="submitCreate"
    >
      <div class="form-body">
        <div class="form-item">
          <span class="form-label">应用名称</span>
          <a-input v-model:value="createForm.appName" placeholder="如:待办事项" maxlength="40" />
        </div>
        <div class="form-item">
          <span class="form-label">需求描述(initPrompt)</span>
          <a-textarea
            v-model:value="createForm.initPrompt"
            placeholder="描述这个应用要做什么,生成时作为基础指令"
            :rows="4"
            maxlength="2000"
          />
        </div>
        <div class="form-item">
          <span class="form-label">生成类型</span>
          <a-select v-model:value="createForm.codeGenType" :options="CODE_GEN_TYPES" style="width: 100%" />
        </div>
        <div class="form-item">
          <span class="form-label">优先级</span>
          <a-input-number v-model:value="createForm.priority" :min="0" :max="999" style="width: 100%" />
          <span class="form-hint">&gt; 0 即加入应用广场</span>
        </div>
      </div>
    </a-modal>

    <!-- 编辑应用弹窗 -->
    <a-modal
      v-model:open="editOpen"
      title="编辑应用"
      :confirm-loading="editing"
      :mask-closable="false"
      ok-text="保存"
      cancel-text="取消"
      @ok="submitEdit"
    >
      <div class="form-body">
        <div class="form-item">
          <span class="form-label">应用名称</span>
          <a-input v-model:value="editForm.appName" maxlength="40" />
        </div>
        <div class="form-item">
          <span class="form-label">需求描述(initPrompt)</span>
          <a-textarea v-model:value="editForm.initPrompt" :rows="4" maxlength="2000" />
        </div>
        <div class="form-item">
          <span class="form-label">封面</span>
          <a-input v-model:value="editForm.cover" placeholder="封面 URL(留空保留原封面)" />
        </div>
        <div class="form-item">
          <span class="form-label">优先级</span>
          <a-input-number v-model:value="editForm.priority" :min="0" :max="999" style="width: 100%" />
          <span class="form-hint">数字越大越靠前,&gt; 0 在应用广场展示</span>
        </div>
      </div>
    </a-modal>
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

.create-btn {
  margin-left: auto;
}

.filter-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.filter-switch-label {
  font-size: 13px;
  color: var(--text-3);
}

/* 封面缩略图:固定小尺寸容器,AppCover 铺满 */
.cover-thumb {
  width: 120px;
  height: 60px;
  border-radius: 6px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: #060a10;
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

/* 弹窗表单 */
.form-body {
  padding-top: 4px;
}

.form-item {
  margin-bottom: 16px;
}

.form-item:last-child {
  margin-bottom: 0;
}

.form-label {
  display: block;
  font-size: 13px;
  color: var(--text-2);
  margin-bottom: 6px;
}

.form-hint {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-3);
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
