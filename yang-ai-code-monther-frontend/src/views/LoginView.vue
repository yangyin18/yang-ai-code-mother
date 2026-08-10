<script setup lang="ts">
/**
 * 登录页
 *
 * 学习点:
 *  1. Antd Form 表单校验(rules 按字段声明,blur 触发)
 *  2. a-form @finish 统一处理提交(a-form-item 包裹的按钮 type=submit 触发)
 *  3. 登录成功后跳回来源页(route.query.redirect),否则回首页
 */
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import AuthLayout from '@/components/AuthLayout.vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

/** 表单数据 */
const form = reactive({
  username: '',
  password: '',
})

/** 提交中标记 */
const loading = ref(false)

/** 表单校验规则 */
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, message: '用户名至少 2 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
  ],
}

/** 提交登录 */
async function handleLogin() {
  loading.value = true
  try {
    await userStore.loginByPassword(form.username.trim(), form.password)
    message.success('登录成功')
    // 支持跳回被拦截前想访问的页面
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '登录失败,请重试')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  // 已登录还访问登录页 → 直接回首页
  if (userStore.isLoggedIn) {
    router.replace('/')
  }
})
</script>

<template>
  <AuthLayout title="欢迎回来" subtitle="登录你的账号,继续你的创作">
    <a-form :model="form" :rules="rules" layout="vertical" @finish="handleLogin">
      <a-form-item label="用户名" name="username">
        <a-input
          v-model:value="form.username"
          size="large"
          placeholder="请输入用户名"
          allow-clear
        />
      </a-form-item>

      <a-form-item label="密码" name="password">
        <a-input-password
          v-model:value="form.password"
          size="large"
          placeholder="请输入密码"
        />
      </a-form-item>

      <a-form-item>
        <button class="glow-btn glow-btn--full" type="submit" :disabled="loading">
          {{ loading ? '登录中...' : '登 录' }}
        </button>
      </a-form-item>
    </a-form>

    <p class="switch-text">
      还没有账号?
      <router-link to="/register" class="switch-link">立即注册</router-link>
    </p>
  </AuthLayout>
</template>

<style scoped>
.switch-text {
  margin: 4px 0 0;
  text-align: center;
  color: var(--text-2);
  font-size: 14px;
}

.switch-link {
  color: #1677ff;
  text-decoration: none;
  font-weight: 600;
}

.switch-link:hover {
  color: #a855f7;
}
</style>
