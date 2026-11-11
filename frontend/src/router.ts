import { createRouter, createWebHashHistory } from 'vue-router'
import Home from './pages/Home.vue'
import Practice from './pages/Practice.vue'
import Result from './pages/Result.vue'
import Cards from './pages/Cards.vue'
import Learn from './pages/Learn.vue'
import Fill from './pages/Fill.vue'
import Wrong from './pages/Wrong.vue'
import Stats from './pages/Stats.vue'
import Build from './pages/Build.vue'
import Bank from './pages/Bank.vue'
import Exam from './pages/Exam.vue'
import Import from './pages/Import.vue'
import Ai from './pages/Ai.vue'
import Setup from './pages/Setup.vue'
import Settings from './pages/Settings.vue'
import Login from './pages/Login.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'home', component: Home, meta: { title: '首页' } },
    { path: '/login', name: 'login', component: Login, meta: { title: '登录', full: true } },
    { path: '/setup', name: 'setup', component: Setup, meta: { title: '开始设置', full: true } },
    { path: '/plan', name: 'plan', component: Setup, meta: { title: '考试计划', full: true } },
    { path: '/cards', name: 'cards', component: Cards, meta: { title: '知识点' } },
    { path: '/learn/:subjectId', name: 'learn', component: Learn, meta: { title: '知识点', full: true } },
    { path: '/fill/:subjectId', name: 'fill', component: Fill, meta: { title: '补答案', full: true } },
    { path: '/wrong', name: 'wrong', component: Wrong, meta: { title: '错题本' } },
    { path: '/build', name: 'build', component: Build, meta: { title: '组卷' } },
    { path: '/bank', name: 'bank', component: Bank, meta: { title: '题库' } },
    { path: '/exam', name: 'exam', component: Exam, meta: { title: '模拟考试' } },
    { path: '/import', name: 'import', component: Import, meta: { title: '导入题库' } },
    { path: '/settings', name: 'settings', component: Settings, meta: { title: '设置' } },
    // 旧的「专业管理」入口：单专业模式下统一回到考试计划
    { path: '/majors', redirect: '/plan' },
    { path: '/ai', name: 'ai', component: Ai, meta: { title: 'AI 校验' } },
    { path: '/stats', name: 'stats', component: Stats, meta: { title: '统计' } },
    { path: '/practice/:id', name: 'practice', component: Practice, meta: { title: '答题', full: true } },
    { path: '/result/:id', name: 'result', component: Result, meta: { title: '练习结果' } }
  ]
})

export default router
