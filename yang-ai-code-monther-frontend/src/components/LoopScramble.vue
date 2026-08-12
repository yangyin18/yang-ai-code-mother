<script setup lang="ts">
/**
 * 循环解密小动画:把一组短短语逐条「乱码 → 解密落定」,再滚到下一条,循环播放。
 * 字符串组成的小动画(非模板式:无矩阵雨/流星,而是字符级有序解密,克制辉光)。
 *
 * 用法:<LoopScramble :phrases="['build.desc', 'render:live']" />
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'

const props = withDefaults(
  defineProps<{ phrases: string[]; duration?: number; hold?: number }>(),
  {
    phrases: () => ['build.desc', 'render:live', 'stream:on'],
    duration: 700,
    hold: 1500,
  },
)

/** 乱码字符集:片假名 + 特殊符号(与 ScrambleText 一致) */
const CHARS = 'アイウエオカキクケコサシスセソタチツテトナニヌネノ0123456789<>/\\|{}[]=+*#@$%&'

const display = ref('')
let timer: ReturnType<typeof setInterval> | null = null
let idx = 0

/** 解密一段:自左向右固定为 target 原文 */
function decryptTo(target: string, frames = 22) {
  if (timer) clearInterval(timer)
  let f = 0
  timer = setInterval(() => {
    f++
    const p = f / frames
    let out = ''
    for (let i = 0; i < target.length; i++) {
      out += i / target.length < p ? target[i] : CHARS[Math.floor(Math.random() * CHARS.length)]
    }
    display.value = out
    if (f >= frames) {
      if (timer) clearInterval(timer)
      timer = null
      display.value = target
      scheduleNext()
    }
  }, Math.max(14, props.duration / frames))
}

/** 停留 hold 后滚到下一条短语继续解密 */
function scheduleNext() {
  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    if (timer) clearInterval(timer)
    timer = null
    idx = (idx + 1) % props.phrases.length
    decryptTo(props.phrases[idx] ?? '')
  }, props.hold)
}

onMounted(() => decryptTo(props.phrases[0] ?? ''))
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  timer = null
})
</script>

<template>
  <span class="loop-scramble mono">{{ display }}</span>
</template>

<style scoped>
.loop-scramble {
  white-space: pre;
  font-size: 11px;
  color: var(--text-3);
}
</style>
