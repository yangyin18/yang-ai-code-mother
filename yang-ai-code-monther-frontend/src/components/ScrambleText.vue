<script setup lang="ts">
/**
 * 字符解密动画:文本自左向右从乱码「解密」成最终文字(黑客解密风)。
 * 非模板式动画(无矩阵雨/流星雨),而是字符级的有序落定:
 * 每个字符随机取自片假名 + ASCII 符号,随进度依次固定为原文。
 *
 * 用法:<ScrambleText text="一句话,生成你的" />
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{ text: string; duration?: number }>(), {
  duration: 1500,
})

/** 乱码字符集:片假名 + 特殊符号(更像「解密中」,而非整屏飘字) */
const CHARS = 'アイウエオカキクケコサシスセソタチツテトナニヌネノ0123456789<>/\\|{}[]=+*#@$%&'

const display = ref('')
let timer: ReturnType<typeof setInterval> | null = null

function scramble(target: string) {
  if (timer) clearInterval(timer)
  const total = 60 // 帧数
  let frame = 0
  timer = setInterval(() => {
    frame++
    const p = frame / total
    let out = ''
    for (let i = 0; i < target.length; i++) {
      // 已越过解密进度线的字符固定为原文,其余仍为乱码 → 自左向右落定
      out += i / target.length < p ? target[i] : CHARS[Math.floor(Math.random() * CHARS.length)]
    }
    display.value = out
    if (frame >= total) {
      if (timer) clearInterval(timer)
      timer = null
      display.value = target
    }
  }, Math.max(16, props.duration / total))
}

onMounted(() => scramble(props.text))
watch(() => props.text, (v) => scramble(v))
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  timer = null
})
</script>

<template>
  <span class="scramble-text mono">{{ display }}</span>
</template>

<style scoped>
.scramble-text {
  white-space: pre;
}
</style>
