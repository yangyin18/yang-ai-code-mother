/**
 * 路由配置
 *
 * 三个页面构成核心闭环:
 *   /       首页      输入需求
 *   /generate 生成页   生成过程动画
 *   /result   结果页   预览 + 代码
 *
 * 首页直接导入(首屏立即渲染),
 * 生成页/结果页用懒加载(进入时才下载对应代码块,优化首屏体积)。
 */
import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
      meta: { title: '首页' },
    },
    {
      path: '/generate',
      name: 'generate',
      component: () => import('@/views/GenerateView.vue'),
      meta: { title: '生成中' },
    },
    {
      path: '/result',
      name: 'result',
      component: () => import('@/views/ResultView.vue'),
      meta: { title: '生成结果' },
    },
    {
      path: '/chat/:appId',
      name: 'chat',
      component: () => import('@/views/ChatView.vue'),
      meta: { title: '应用对话' },
    },
    {
      path: '/project/:appId',
      name: 'project',
      component: () => import('@/views/VueProjectView.vue'),
      meta: { title: 'Vue 项目' },
    },
    {
      path: '/conversations',
      name: 'conversations',
      component: () => import('@/views/MyConversationsView.vue'),
      meta: { title: '我的对话' },
    },
    {
      path: '/admin/chat',
      name: 'chatAdmin',
      component: () => import('@/views/ChatAdminView.vue'),
      meta: { title: '对话管理' },
    },
    {
      path: '/admin/apps',
      name: 'appAdmin',
      component: () => import('@/views/AppAdminView.vue'),
      meta: { title: '应用管理' },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: '登录' },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { title: '注册' },
    },
  ],
})

/**
 * 全局后置钩子:每次路由切换后更新浏览器标签页标题
 */
router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · AI 零代码应用平台` : 'AI 零代码应用平台'
})

export default router
