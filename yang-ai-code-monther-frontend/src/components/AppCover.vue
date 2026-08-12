<script setup lang="ts">
/**
 * 共享应用封面:简洁终端风封面,不用图片。
 *
 * 暗色渐变 + 细网格背景(色相按应用名稳定生成,每张卡片不同但稳定),
 * 中央一行 `>_ 应用名`:挂载时轻声把名字打完就停,光标柔和闪烁。
 * 克制不花哨:无扫描线、无跳动、不循环重打。
 * 左上角保留 LIVE 徽章(已部署)。
 *
 * 用法:<AppCover :name="app.appName" :cover="app.cover" :live="!!app.deployUrl" />
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    name?: string
    cover?: string
    live?: boolean
  }>(),
  { name: '', cover: '', live: false },
)

/** 应用名过长时截断显示的字数(超出加 …),避免小尺寸卡片溢出 */
const DISPLAY_MAX = 14

/** 已打出的字符(挂载后安静打一遍即停) */
const typed = ref('')
let timer: number | null = null

function clearTimer() {
  if (timer !== null) {
    window.clearTimeout(timer)
    timer = null
  }
}

function typeOnce(fullText: string) {
  clearTimer()
  typed.value = ''
  const text =
    fullText.length > DISPLAY_MAX ? `${fullText.slice(0, DISPLAY_MAX)}…` : fullText
  let i = 0
  const step = () => {
    i++
    typed.value = text.slice(0, i)
    if (i < text.length) timer = window.setTimeout(step, 45)
  }
  timer = window.setTimeout(step, 200)
}

watch(() => props.name, (n) => typeOnce(n || 'AI'), { immediate: true })
onBeforeUnmount(clearTimer)

/** 按应用名 hash 稳定取两个色相(用于暗色渐变,亮度/饱和度压暗) */
const h1 = computed(() => {
  let h = 0
  for (let i = 0; i < props.name.length; i++) h = (h * 31 + props.name.charCodeAt(i)) % 360
  return h
})
const h2 = computed(() => (h1.value + 48) % 360)
</script>

<template>
  <div class="app-cover">
    <div
      class="cover-ph"
      :style="{
        '--h1': h1,
        '--h2': h2,
        backgroundImage:
          'radial-gradient(120% 90% at 18% 0%, hsl(var(--h1) 42% 17%), transparent 55%),' +
          'radial-gradient(120% 90% at 82% 100%, hsl(var(--h2) 40% 13%), transparent 55%),' +
          'linear-gradient(rgba(0,255,157,0.05) 1px, transparent 1px),' +
          'linear-gradient(90deg, rgba(0,255,157,0.05) 1px, transparent 1px)',
        backgroundSize: '100% 100%, 100% 100%, 22px 22px, 22px 22px',
        backgroundColor: '#0a0e14',
      }"
    >
      <span class="cover-line mono">
        <span class="cover-prompt">&gt;_</span>
        <span class="cover-text">{{ typed }}</span>
        <span class="cover-cursor" />
      </span>
    </div>
    <span v-if="live" class="cover-badge">LIVE</span>
  </div>
</template>

<style scoped>
.app-cover {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: #060a10;
  /* 让占位内容字号随容器宽度自适应(卡片封面 / 会话小图共用) */
  container-type: inline-size;
}

/* 暗色终端占位:低饱和渐变 + 细网格 + 一行 `>_ 应用名` */
.cover-ph {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.cover-line {
  display: inline-flex;
  align-items: center;
  gap: 1.2cqw;
  max-width: 88%;
  white-space: nowrap;
  overflow: hidden;
}

.cover-prompt {
  color: var(--primary);
  font-weight: 700;
  font-size: 12cqw;
  opacity: 0.75;
  text-shadow: 0 0 8px rgba(0, 255, 157, 0.2);
  flex-shrink: 0;
}

.cover-text {
  color: rgba(215, 227, 234, 0.92);
  font-size: 12cqw;
  font-weight: 500;
  letter-spacing: 0.3px;
}

/* 块状光标:柔和闪烁(半透明渐变而非硬切),不抢眼 */
.cover-cursor {
  width: 0.9cqw;
  height: 11cqw;
  flex-shrink: 0;
  background: var(--primary);
  opacity: 0.55;
  animation: cursor-blink 1.2s ease-in-out infinite;
}

@keyframes cursor-blink {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 0.15;
  }
}

/* 已部署徽章:绿 LIVE。放左上角:右上角让位给卡片上的删除 ✕,避免重叠 */
.cover-badge {
  position: absolute;
  top: 10px;
  left: 10px;
  font-size: 10px;
  padding: 2px 9px;
  border-radius: 999px;
  color: var(--success);
  border: 1px solid rgba(0, 255, 157, 0.5);
  background: rgba(0, 0, 0, 0.55);
  box-shadow: 0 0 5px rgba(0, 255, 157, 0.12);
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  letter-spacing: 0.6px;
  z-index: 2;
}
</style>
