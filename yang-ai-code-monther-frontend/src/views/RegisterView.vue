<script setup lang="ts">
/**
 * 注册页
 *
 * 学习点:
 *  1. 确认密码的「交叉校验」用自定义 validator(比较 confirmPassword 与 password)
 *  2. 注册成功后自动登录(调用 loginByPassword),让用户直接进入平台
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import AuthLayout from '@/components/AuthLayout.vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

/** 表单数据 */
const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
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
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      // 自定义校验器:两次密码必须一致
      validator: (_rule: unknown, value: string) =>
        value === form.password
          ? Promise.resolve()
          : Promise.reject(new Error('两次输入的密码不一致')),
      trigger: 'blur',
    },
  ],
}

/** 提交注册 */
async function handleRegister() {
  loading.value = true
  try {
    // 1. 注册
    await userStore.registerUser(form.username.trim(), form.password)
    // 2. 自动登录并进入平台
    await userStore.loginByPassword(form.username.trim(), form.password)
    message.success('注册成功,已为你自动登录')
    router.push('/')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '注册失败,请重试')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (userStore.isLoggedIn) {
    router.replace('/')
  }
})
</script>

<template>
  <AuthLayout title="创建账号" subtitle="注册只需 10 秒,马上开始你的创作">
    <a-form :model="form" :rules="rules" layout="vertical" @finish="handleRegister">
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
          placeholder="至少 6 位"
        />
      </a-form-item>

      <a-form-item label="确认密码" name="confirmPassword">
        <a-input-password
          v-model:value="form.confirmPassword"
          size="large"
          placeholder="再次输入密码"
        />
      </a-form-item>

      <a-form-item>
        <button class="glow-btn glow-btn--full" type="submit" :disabled="loading">
          {{ loading ? '注册中...' : '注 册' }}
        </button>
      </a-form-item>
    </a-form>

    <p class="switch-text">
      已有账号?
      <router-link to="/login" class="switch-link">直接登录</router-link>
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
  color: #a5b4fc;
  text-decoration: none;
  font-weight: 600;
}

.switch-link:hover {
  color: #c7d2fe;
}
</style>
