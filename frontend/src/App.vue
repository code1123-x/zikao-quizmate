<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, majorStorageKey, setActiveMajor, type AuthUser, type MajorView } from './api'
import { toast, toastText } from './toast'
import AppDialog from './components/AppDialog.vue'

const route = useRoute()
const router = useRouter()

const full = computed(() => route.meta.full === true)

const navs = [
  { key: 'home', label: '首页', ico: '◉', path: '/' },
  { key: 'cards', label: '知识点', ico: '◈', path: '/cards' },
  { key: 'build', label: '练习', ico: '▤', path: '/build' },
  { key: 'exam', label: '模拟考试', ico: '⏱', path: '/exam' },
  { key: 'bank', label: '题库', ico: '☰', path: '/bank' },
  { key: 'wrong', label: '错题本', ico: '✕', path: '/wrong' },
  { key: 'stats', label: '统计', ico: '◫', path: '/stats' },
  { key: 'import', label: '导入', ico: '⇪', path: '/import' }
]

const majors = ref<MajorView[]>([])
const majorId = ref<number | null>(null)
const currentUser = ref<AuthUser | null>(null)
const ready = ref(false)
const majorVersion = ref(0)
const currentMajor = computed(() => majors.value.find((m) => m.id === majorId.value) ?? majors.value[0] ?? null)

function isActive(key: string) {
  if (key === 'home') return route.path === '/'
  if (key === 'cards') return route.path.startsWith('/cards') || route.path.startsWith('/learn')
  if (key === 'build') return route.path.startsWith('/build')
  if (key === 'exam') return route.path.startsWith('/exam')
  if (key === 'bank') return route.path.startsWith('/bank')
  if (key === 'wrong') return route.path.startsWith('/wrong')
  if (key === 'stats') return route.path.startsWith('/stats')
  if (key === 'import') return route.path.startsWith('/import')
  return false
}

function open(nav: { key: string; path: string; label: string }) {
  if (nav.path) {
    router.push(nav.path)
    return
  }
  toast(`${nav.label}将在下一个版本上线`)
}

async function loadMajors(prefer?: number | null) {
  try {
    const list = await api.majors()
    majors.value = list
    const saved = Number(localStorage.getItem(majorStorageKey()) || 0)
    const want = prefer ?? majorId.value ?? saved
    const chosen = list.find((m) => m.id === want) ?? list[0] ?? null
    majorId.value = chosen?.id ?? null
    setActiveMajor(majorId.value)
    if (majorId.value) localStorage.setItem(majorStorageKey(), String(majorId.value))
    majorVersion.value++
  } catch (e) {
    toast((e as Error).message)
  } finally {
    ready.value = true
  }
}

function onMajorsChanged(e: Event) {
  const prefer = (e as CustomEvent<{ prefer?: number }>).detail?.prefer
  loadMajors(prefer)
}

function onAuthRequired() {
  currentUser.value = null
  localStorage.removeItem('quizmate.userId')
  if (route.path !== '/login') router.replace('/login')
}

onMounted(async () => {
  window.addEventListener('quizmate:majors', onMajorsChanged)
  window.addEventListener('quizmate:auth-required', onAuthRequired)
  await bootstrap()
})

async function bootstrap() {
  try {
    currentUser.value = await api.authMe()
    localStorage.setItem('quizmate.userId', String(currentUser.value.id))
  } catch {
    currentUser.value = null
    localStorage.removeItem('quizmate.userId')
    ready.value = true
    if (route.path !== '/login') router.replace('/login')
    return
  }
  if (route.path === '/login') router.replace('/')

  let setupDone = true
  try {
    const status = await api.setupStatus()
    setupDone = status.setupDone
  } catch {
    /* 状态接口失败时不要拦住老用户，按已完成处理 */
  }
  await loadMajors()
  if (!setupDone) {
    const deferred = localStorage.getItem(`quizmate.setupDeferred.${currentUser.value?.id}`) === '1'
    if (!deferred && route.path !== '/setup') router.replace('/setup')
  } else if (route.path === '/setup') {
    router.replace('/')
  }
}

onBeforeUnmount(() => {
  window.removeEventListener('quizmate:majors', onMajorsChanged)
  window.removeEventListener('quizmate:auth-required', onAuthRequired)
})
</script>

<template>
  <div class="app" :class="{ fullscreen: full }">
    <aside v-if="!full" class="sidebar">
      <div class="brand"><span class="logo" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H11v17H6.5A2.5 2.5 0 0 0 4 22V5.5Z"/><path d="M20 5.5A2.5 2.5 0 0 0 17.5 3H13v17h4.5A2.5 2.5 0 0 1 20 22V5.5Z"/><path d="m8 9 1.5 1.5L12 8"/></svg></span><span>自考刷题</span></div>
      <button
        v-for="nav in navs"
        :key="nav.key"
        class="nav"
        :class="{ active: isActive(nav.key) }"
        @click="open(nav)"
      >
        <span class="ico">{{ nav.ico }}</span>{{ nav.label }}
      </button>
    </aside>

    <main>
      <div v-if="!full" class="topbar">
        <div class="top-actions">
          <div v-if="currentMajor" class="major-pick" title="当前专业">
            <span class="mp-label">专业</span>
            <span class="mp-name">{{ currentMajor.name }}</span>
          </div>
          <button v-if="majors.length" class="plan-btn" title="调整本次考试科目和日期" @click="router.push('/plan')">
            📅 考试计划
          </button>
          <button class="gear" title="设置" aria-label="设置" @click="router.push('/settings')">⚙</button>
          <button
            class="avatar"
            :title="currentUser ? `当前账号：${currentUser.username}` : '设置'"
            aria-label="账号设置"
            @click="router.push('/settings')"
          >
            {{ currentUser?.username?.slice(0, 1) || '我' }}
          </button>
        </div>
      </div>
      <router-view
        v-if="ready && (currentUser || route.path === '/login')"
        :key="$route.fullPath + '-' + majorVersion"
      />
      <div v-else class="page"><div class="card empty">正在加载…</div></div>
    </main>
  </div>
  <div class="toast" :class="{ show: !!toastText }">{{ toastText }}</div>
  <AppDialog />
</template>

<style scoped>
.major-pick {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: var(--card);
  color: var(--fg);
  font-family: inherit;
  cursor: default;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.major-pick:hover {
  border-color: var(--line-strong);
  box-shadow: var(--shadow-sm);
}
.mp-label {
  font-size: 11.5px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--muted);
  white-space: nowrap;
}
.mp-name {
  max-width: 190px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--fg);
  font-size: 13px;
  font-weight: 600;
}
.plan-btn {
  height: 34px;
  padding: 0 12px;
  border-radius: 11px;
  border: 1px solid var(--line);
  background: var(--card);
  color: var(--muted);
  font-size: 12.5px;
  font-weight: 600;
  white-space: nowrap;
  transition: 0.15s;
}
.plan-btn:hover {
  color: var(--primary);
  border-color: var(--primary);
  background: var(--primary-soft);
}
@media (max-width: 720px) {
  .mp-label {
    display: none;
  }
  .major-pick {
    padding: 0 8px;
  }
  .mp-name {
    max-width: 120px;
    font-size: 12.5px;
  }
  .plan-btn {
    padding: 0 9px;
    font-size: 11.5px;
  }
}

@media (max-width: 520px) {
  .top-actions {
    gap: 6px;
  }
  .avatar {
    display: none;
  }
  .plan-btn {
    padding: 0 8px;
    font-size: 11px;
  }
}
</style>
