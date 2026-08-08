<script setup lang="ts">
/**
 * 认证页共用布局(左右分栏,大厂登录页风格)
 *
 *   ┌────────────────────────────────────────────┐
 *   │  ⚡ 品牌区         │   表单区                 │
 *   │   AI 零代码平台    │   (由页面通过插槽传入)    │
 *   │   一句文案+特性列表 │                         │
 *   └────────────────────────────────────────────┘
 *
 * 学习点:插槽(slot)—— 左右结构/品牌文案在这里复用,
 * 登录页和注册页只往里填各自不同的表单内容。
 */
defineProps<{
  /** 表单区大标题,如「欢迎回来」 */
  title: string
  /** 表单区副标题 */
  subtitle: string
}>()
</script>

<template>
  <div class="auth-layout">
    <div class="auth-card">
      <!-- 左:品牌区 -->
      <div class="auth-brand">
        <div class="brand-mark">⚡</div>
        <h2 class="brand-title">AI 零代码平台</h2>
        <p class="brand-slogan">
          一句话生成可运行的应用<br />
          从创意到上线,只需几分钟
        </p>
        <ul class="brand-features">
          <li>🧠 智能理解你的需求</li>
          <li>⚡ 秒级生成完整应用</li>
          <li>📦 生成的代码一键带走</li>
        </ul>
      </div>

      <!-- 右:表单区(页面通过默认插槽传入) -->
      <div class="auth-form">
        <h1 class="form-title">{{ title }}</h1>
        <p class="form-subtitle">{{ subtitle }}</p>
        <slot />
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-layout {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: calc(100vh - 160px);
  animation: fade-up 0.5s ease both;
}

.auth-card {
  display: grid;
  grid-template-columns: 1fr 1fr;
  width: 100%;
  max-width: 880px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 24px;
  overflow: hidden;
  box-shadow: var(--shadow);
}

@media (max-width: 720px) {
  .auth-card {
    grid-template-columns: 1fr;
  }

  .auth-brand {
    display: none;
  }
}

/* 左:品牌区(渐变底 + 点阵) */
.auth-brand {
  padding: 44px 40px;
  background-image:
    linear-gradient(160deg, rgba(99, 102, 241, 0.18), rgba(139, 92, 246, 0.08)),
    radial-gradient(rgba(255, 255, 255, 0.08) 1px, transparent 1px);
  background-size:
    100% 100%,
    22px 22px;
  border-right: 1px solid var(--border);
}

.brand-mark {
  width: 46px;
  height: 46px;
  display: grid;
  place-items: center;
  font-size: 22px;
  background: var(--gradient);
  border-radius: 13px;
  box-shadow: 0 0 24px rgba(99, 102, 241, 0.5);
  margin-bottom: 20px;
}

.brand-title {
  margin: 0 0 12px;
  font-size: 22px;
  font-weight: 700;
}

.brand-slogan {
  margin: 0 0 26px;
  color: var(--text-2);
  font-size: 14px;
  line-height: 1.8;
}

.brand-features {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.brand-features li {
  color: var(--text-2);
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 右:表单区 */
.auth-form {
  padding: 44px 40px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.form-title {
  margin: 0 0 8px;
  font-size: 26px;
  font-weight: 800;
}

.form-subtitle {
  margin: 0 0 26px;
  color: var(--text-2);
  font-size: 14px;
}

@keyframes fade-up {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
