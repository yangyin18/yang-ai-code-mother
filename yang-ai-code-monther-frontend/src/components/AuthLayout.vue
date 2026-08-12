<script setup lang="ts">
/**
 * 认证页共用布局(终端会话风)
 *
 *   ┌────────────────────────────────────────────┐
 *   │  左:终端品牌区        │   右:表单区          │
 *   │   ●●● root@matrix    │   $ 标题            │
 *   │   >_ AI.CODE_TERMINAL │   > 副标题          │
 *   │   $ echo "..."       │   (登录/注册表单)    │
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
      <!-- 左:终端品牌区 -->
      <div class="auth-brand">
        <div class="term-bar">
          <span class="term-dot red" />
          <span class="term-dot yellow" />
          <span class="term-dot green" />
          <span class="term-title mono">root@matrix:~/auth</span>
        </div>
        <div class="brand-body">
          <div class="brand-mark mono"><span class="prompt-sym">>_</span> AI.CODE_TERMINAL</div>
          <p class="brand-slogan mono">
            <span class="cmd-line"><span class="prompt-sym">$</span> echo "一句话生成可运行的应用"</span>
            <span class="cmd-out">一句话生成可运行的应用<br />从创意到上线,只需几分钟</span>
          </p>
          <ul class="brand-features mono">
            <li><span class="prompt-sym">✓</span> 智能理解你的需求</li>
            <li><span class="prompt-sym">✓</span> 秒级生成完整应用</li>
            <li><span class="prompt-sym">✓</span> 生成的代码一键带走</li>
          </ul>
        </div>
      </div>

      <!-- 右:表单区(页面通过默认插槽传入) -->
      <div class="auth-form">
        <h1 class="form-title mono"><span class="prompt-sym">$</span> {{ title }}</h1>
        <p class="form-subtitle mono">&gt; {{ subtitle }}</p>
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
  border-radius: var(--radius);
  overflow: hidden;
  box-shadow: var(--shadow), 0 0 0 1px rgba(0, 255, 157, 0.06);
}

@media (max-width: 720px) {
  .auth-card {
    grid-template-columns: 1fr;
  }

  .auth-brand {
    display: none;
  }
}

/* 左:终端品牌区(近黑底 + 绿色网格) */
.auth-brand {
  display: flex;
  flex-direction: column;
  background:
    linear-gradient(rgba(0, 255, 157, 0.035) 1px, transparent 1px),
    linear-gradient(90deg, rgba(0, 255, 157, 0.035) 1px, transparent 1px),
    #060a10;
  background-size: 26px 26px;
  border-right: 1px solid var(--border);
}

.brand-body {
  flex: 1;
  padding: 30px 28px;
  display: flex;
  flex-direction: column;
}

.brand-mark {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 0.4px;
  color: var(--text);
  margin-bottom: 26px;
}

.prompt-sym {
  color: var(--primary);
  font-weight: 700;
  text-shadow: 0 0 8px rgba(0, 255, 157, 0.6);
  margin-right: 4px;
}

.brand-slogan {
  margin: 0 0 22px;
  color: var(--text-2);
  font-size: 13px;
  line-height: 1.9;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.cmd-line {
  color: var(--text-2);
}

.cmd-out {
  color: var(--primary-2);
}

.brand-features {
  list-style: none;
  margin: 0 0 20px;
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
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 0.3px;
}

.form-subtitle {
  margin: 0 0 26px;
  color: var(--text-2);
  font-size: 13px;
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
