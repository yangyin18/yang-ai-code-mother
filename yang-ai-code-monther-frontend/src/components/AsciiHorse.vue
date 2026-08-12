<script setup lang="ts">
/**
 * 字符串组成的奔跑的小马（循环逐帧动画）。
 *
 * 4 帧马腿交错、马鬃飘动的完整奔跑循环（源自 Eadweard Muybridge 的奔马连续摄影，
 * 是 ASCII 动画里的经典「奔跑的马」）。马头带耳朵、长脸，尾巴甩动，跑起来的感觉很足。
 * 非模板式动画（无矩阵雨/流星雨），克制低调。
 *
 * 用法：<AsciiHorse :fps="8" />
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'

const props = withDefaults(defineProps<{ fps?: number; size?: number }>(), {
  fps: 8,
  size: 13,
})

/** 4 帧奔跑的小马（每帧等宽等高，避免跳动）。马头朝左，全速奔跑中。 */
const FRAMES: string[][] = [
  [
    '      _     ',
    '     ( )    ',
    '     _|_    ',
    '   \\/ | \\   ',
    '     _|_ \\  ',
    '     \\ / _  ',
    '      /\\/   ',
    '    _/      ',
  ],
  [
    '      _     ',
    '     ( )    ',
    '     _|_    ',
    '     _||_\\  ',
    '     _|_    ',
    '    /  |    ',
    '    - | |   ',
    '      _|    ',
  ],
  [
    '      _     ',
    '     ( )    ',
    '     _|_    ',
    '     \\|/    ',
    '     _/_    ',
    '    /   \\  _',
    '   /     \\/ ',
    ' _/         ',
  ],
  [
    '      _     ',
    '     ( )    ',
    '     _|_    ',
    '     _||_\\  ',
    '     _|_    ',
    '     | /    ',
    '     | \\_   ',
    '    _|      ',
  ],
]

const frameIdx = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  timer = setInterval(() => {
    frameIdx.value = (frameIdx.value + 1) % FRAMES.length
  }, Math.max(60, Math.round(1000 / props.fps)))
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  timer = null
})
</script>

<template>
  <pre class="ascii-horse mono" :style="{ fontSize: `${props.size}px` }">{{ FRAMES[frameIdx]?.join('\n') ?? '' }}</pre>
</template>

<style scoped>
.ascii-horse {
  margin: 0;
  line-height: 1.15;
  color: var(--primary);
  opacity: 0.85;
  text-shadow: 0 0 8px rgba(0, 255, 157, 0.18);
  user-select: none;
}
</style>
