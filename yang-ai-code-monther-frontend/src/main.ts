/**
 * 应用入口
 *
 * 职责:创建 Vue 应用实例,并依次注册
 *  1. Pinia    状态管理
 *  2. Router   路由
 *  3. Antd     组件库(全局注册,开发期省事;也可按需引入)
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import '@/styles/index.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(Antd)

app.mount('#app')