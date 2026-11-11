<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, TYPE_NAME, type WrongItem, type WrongPage, type WrongSubject } from '../api'
import { toast } from '../toast'

const router = useRouter()

const subjects = ref<WrongSubject[]>([])
const activeId = ref<number | null>(null)
const page = ref<WrongPage | null>(null)
const pageNo = ref(1)
const size = 20
const loading = ref(true)
const error = ref('')
const starting = ref(false)
const type = ref('')
const keyword = ref('')
const sort = ref('recent')
const detail = ref<WrongItem | null>(null)
const selected = ref<Set<number>>(new Set())

const active = computed(() => subjects.value.find((s) => s.subjectId === activeId.value))
const totalAll = computed(() => subjects.value.reduce((a, s) => a + s.count, 0))
const total = computed(() => page.value?.total ?? 0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const allPageSelected = computed(() => {
  const items = page.value?.items ?? []
  return items.length > 0 && items.every((q) => selected.value.has(q.id))
})

onMounted(load)
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

async function load() {
  loading.value = true
  error.value = ''
  selected.value = new Set()
  try {
    subjects.value = await api.wrongSummary()
    if (subjects.value.length) {
      const keep = subjects.value.find((s) => s.subjectId === activeId.value)
      activeId.value = keep?.subjectId ?? subjects.value[0].subjectId
      await loadPage()
    } else {
      activeId.value = null
      page.value = null
    }
  } catch (e) {
    subjects.value = []
    page.value = null
    error.value = (e as Error).message
    toast((e as Error).message)
  } finally {
    loading.value = false
  }
  window.removeEventListener('keydown', onKey)
  window.addEventListener('keydown', onKey)
}

async function select(id: number) {
  activeId.value = id
  pageNo.value = 1
  selected.value = new Set()
  await loadPage()
}

async function loadPage() {
  if (!activeId.value) return
  loading.value = true
  error.value = ''
  try {
    page.value = await api.wrongList({
      subjectId: activeId.value,
      type: type.value || undefined,
      q: keyword.value.trim() || undefined,
      sort: sort.value,
      page: pageNo.value,
      size
    })
  } catch (e) {
    page.value = null
    error.value = (e as Error).message
    toast((e as Error).message)
  } finally {
    loading.value = false
  }
}

function search() {
  pageNo.value = 1
  selected.value = new Set()
  loadPage()
}

function resetFilters() {
  type.value = ''
  keyword.value = ''
  sort.value = 'recent'
  pageNo.value = 1
  selected.value = new Set()
  loadPage()
}

function toggleSelect(id: number) {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}

function clearSelected() {
  selected.value = new Set()
}

function togglePage() {
  const next = new Set(selected.value)
  if (allPageSelected.value) {
    page.value?.items.forEach((q) => next.delete(q.id))
  } else {
    page.value?.items.forEach((q) => next.add(q.id))
  }
  selected.value = next
}

function goPage(next: number) {
  if (next < 1 || next > totalPages.value) return
  pageNo.value = next
  loadPage()
}

function openDetail(q: WrongItem) {
  detail.value = q
}

function closeDetail() {
  detail.value = null
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && detail.value) {
    e.preventDefault()
    closeDetail()
  }
}

async function practice() {
  if (!activeId.value || !active.value || starting.value) return
  starting.value = true
  try {
    const session = await api.createSession({
      subjectId: activeId.value,
      mode: 'WRONG',
      count: Math.min(active.value.count, 100),
      onlyWrong: true,
      types: type.value ? [type.value] : undefined
    })
    router.push(`/practice/${session.id}`)
  } catch (e) {
    toast((e as Error).message)
  } finally {
    starting.value = false
  }
}

async function practiceSelected() {
  if (!activeId.value || !selected.value.size || starting.value) return
  starting.value = true
  try {
    const ids = [...selected.value]
    const session = await api.createSession({
      subjectId: activeId.value,
      mode: 'WRONG',
      count: ids.length,
      questionIds: ids
    })
    router.push(`/practice/${session.id}`)
  } catch (e) {
    toast((e as Error).message)
  } finally {
    starting.value = false
  }
}

function isRight(q: WrongItem, key: string) {
  return q.answer.includes(key)
}

function when(s?: string) {
  if (!s) return ''
  const date = new Date(s)
  if (Number.isNaN(date.getTime())) return s.slice(0, 10)
  const local = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
  const now = new Date()
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  return local === today ? '今天' : local
}

function stemPreview(stem: string) {
  return stem.replace(/\s+/g, ' ').trim()
}
</script>

<template>
  <div class="page wrong-page">
    <header class="wrong-hero">
      <div class="hero-main">
        <div class="hero-top">
          <button class="back-icon" title="返回首页" aria-label="返回首页" @click="router.push('/')">←</button>
          <h1>错题本</h1>
        </div>
        <p>共 {{ totalAll }} 道错题</p>
      </div>
      <button
        v-if="active && active.count"
        class="btn practice-cta"
        :disabled="starting"
        @click="practice"
      >
        {{ starting ? '正在组卷…' : '练习本学科错题' }}
      </button>
    </header>

    <div v-if="error" class="card empty">
      加载失败：{{ error }}
      <div style="margin-top: 12px">
        <button class="btn sm" @click="load">重试</button>
      </div>
    </div>

    <div v-else-if="!loading && !subjects.length" class="card empty">
      还没有错题。去刷几题吧，做错的会自动进来。
      <div style="margin-top: 12px">
        <button class="btn" @click="router.push('/')">去刷题</button>
      </div>
    </div>

    <template v-else>
      <div class="chips">
        <button
          v-for="s in subjects"
          :key="s.subjectId"
          class="chip"
          :class="{ on: s.subjectId === activeId }"
          @click="select(s.subjectId)"
        >
          <span class="dot" :style="{ background: s.color }"></span>
          {{ s.name }}
        </button>
      </div>

      <div class="toolbar card">
        <select v-model="type" class="sel" @change="search">
          <option value="">全部题型</option>
          <option value="SINGLE">单选题</option>
          <option value="MULTIPLE">多选题</option>
          <option value="JUDGE">判断题</option>
        </select>
        <input
          v-model="keyword"
          class="kw"
          placeholder="搜索题干 / 答案 / 来源"
          @keyup.enter="search"
        />
        <select v-model="sort" class="sel" @change="search">
          <option value="recent">最近做错</option>
          <option value="count">错得最多</option>
          <option value="oldest">最早做错</option>
          <option value="type">按题型</option>
        </select>
        <button class="btn sm" @click="search">筛选</button>
        <button class="mini" @click="resetFilters">重置</button>
        <button v-if="page?.items.length" class="mini" @click="togglePage">
          {{ allPageSelected ? '取消本页' : '全选本页' }}
        </button>
        <template v-if="selected.size">
          <span class="selected-count">已选 {{ selected.size }} 题</span>
          <button class="btn sm" :disabled="starting" @click="practiceSelected">练习选中</button>
          <button class="mini" @click="clearSelected">清空</button>
        </template>
      </div>

      <div v-if="loading" class="card empty">正在加载…</div>

      <div v-else-if="page && !page.items.length" class="card empty">
        没有符合条件的错题
      </div>

      <div v-else-if="page" class="list">
        <div v-for="q in page.items" :key="q.id" class="card q" @click="openDetail(q)">
          <div class="q-top">
            <input
              class="sel-box"
              type="checkbox"
              :checked="selected.has(q.id)"
              @click.stop
              @change="toggleSelect(q.id)"
            />
            <span class="pill">{{ TYPE_NAME[q.type] ?? q.type }}</span>
            <span class="muted src">{{ q.year ? q.year + ' 年 · ' : '' }}{{ q.source }}</span>
            <span v-if="q.wrongCount > 1" class="bad">错 {{ q.wrongCount }} 次</span>
          </div>
          <div class="stem">{{ stemPreview(q.stem) }}</div>
          <div class="q-foot">
            <span class="muted">上次做错：{{ when(q.lastWrongAt) }}</span>
            <span class="hint">查看答案与解析 →</span>
          </div>
        </div>
      </div>

      <div v-if="page && totalPages > 1" class="pager">
        <button class="mini" :disabled="pageNo <= 1" @click="goPage(pageNo - 1)">上一页</button>
        <span class="muted">{{ pageNo }} / {{ totalPages }}</span>
        <button class="mini" :disabled="pageNo >= totalPages" @click="goPage(pageNo + 1)">下一页</button>
      </div>
    </template>

    <div v-if="detail" class="wrong-mask" @click.self="closeDetail">
      <div class="wrong-dialog">
        <header class="wd-head">
          <div>
            <span class="pill">{{ TYPE_NAME[detail.type] ?? detail.type }}</span>
            <span class="muted">{{ detail.year ? detail.year + ' 年 · ' : '' }}{{ detail.source }}</span>
          </div>
          <button class="wd-x" title="关闭" @click="closeDetail">✕</button>
        </header>
        <div class="wd-body">
          <div class="qstem">{{ detail.stem }}</div>
          <div v-if="detail.options?.length" class="opts">
            <div
              v-for="o in detail.options"
              :key="o.key"
              class="opt ro"
              :class="{ right: isRight(detail, o.key) }"
            >
              <span class="k">{{ o.key }}</span>
              <span class="t">{{ o.text }}</span>
            </div>
          </div>
          <div class="answer-line">
            正确答案：<b>{{ detail.answer }}</b>
          </div>
          <div v-if="detail.analysis" class="analysis">{{ detail.analysis }}</div>
          <div class="muted wd-time">上次做错：{{ when(detail.lastWrongAt) }} · 累计错 {{ detail.wrongCount }} 次</div>
        </div>
        <footer class="wd-foot">
          <button class="btn ghost sm" @click="closeDetail">关闭</button>
          <button v-if="active && active.count" class="btn sm" @click="closeDetail(); practice()">练习错题</button>
        </footer>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wrong-page {
  max-width: 980px;
}
.wrong-hero {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
  margin-bottom: 18px;
  padding: 18px 22px;
  border: 1px solid #e0e2ff;
  border-radius: 20px;
  background:
    radial-gradient(340px 180px at 92% -20%, rgba(109, 94, 252, .14), transparent 70%),
    radial-gradient(260px 180px at 0% 130%, rgba(14, 165, 233, .09), transparent 70%),
    linear-gradient(135deg, #f8f7ff 0%, #fcfcff 58%, #f7f9ff 100%);
  box-shadow: 0 18px 42px -32px rgba(64, 58, 180, .55);
}
.hero-main {
  min-width: 0;
}
.hero-top {
  display: flex;
  align-items: center;
  gap: 10px;
}
.back-icon {
  flex: none;
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: 1px solid rgba(91, 91, 224, .14);
  border-radius: 10px;
  background: var(--card);
  color: var(--muted);
  font-family: inherit;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  transition: .15s;
}
.back-icon:hover {
  color: var(--primary);
  border-color: var(--primary);
  background: var(--primary-soft);
  transform: translateX(-1px);
}
.back-icon:focus-visible {
  outline: none;
  box-shadow: var(--ring);
}
.hero-top h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.1;
  letter-spacing: -.03em;
  color: var(--fg);
}
.wrong-hero p {
  margin: 4px 0 0 42px;
  color: var(--muted);
  font-size: 12.5px;
}
.practice-cta {
  flex: none;
  align-self: center;
}
.chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: var(--card);
  font-size: 13px;
}
.chip.on {
  border-color: var(--primary);
  background: var(--primary-soft);
  color: var(--primary);
}
.chip .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.chip .cnt {
  font-size: 11px;
  opacity: 0.7;
}
.toolbar {
  display: flex;
  gap: 9px;
  align-items: center;
  flex-wrap: wrap;
  padding: 12px 14px;
  margin-bottom: 14px;
}
.sel {
  height: 36px;
  min-width: 120px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--card);
  color: var(--fg);
  font-family: inherit;
  font-size: 12.5px;
}
.kw {
  flex: 1;
  min-width: 180px;
  height: 36px;
  padding: 0 11px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--card);
  color: var(--fg);
  font-family: inherit;
  font-size: 12.5px;
}
.sel:focus,
.kw:focus {
  outline: none;
  border-color: var(--primary);
  box-shadow: var(--ring);
}
.toolbar-total {
  margin-left: auto;
  font-size: 12px;
}
.selected-count {
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--primary-soft);
  color: var(--primary);
  font-size: 11.5px;
  font-weight: 700;
}
.sel-box {
  flex: none;
  width: 15px;
  height: 15px;
  accent-color: var(--primary);
  cursor: pointer;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.q {
  padding: 14px 16px;
  cursor: pointer;
  transition: border-color .15s, box-shadow .15s, transform .15s;
}
.q:hover {
  border-color: color-mix(in srgb, var(--primary) 28%, var(--line));
  box-shadow: var(--shadow);
  transform: translateY(-1px);
}
.q-top {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 7px;
}
.src {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 55%;
}
.bad {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--bad);
  background: var(--bad-soft);
  padding: 2px 8px;
  border-radius: 999px;
}
.stem {
  font-size: 14.5px;
  line-height: 1.65;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.q-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 9px;
  font-size: 11.5px;
}
.hint {
  color: var(--primary);
  white-space: nowrap;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  margin-top: 18px;
}
.pager .muted {
  font-size: 12px;
}
.wrong-mask {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(15, 18, 40, .42);
  backdrop-filter: blur(3px);
}
.wrong-dialog {
  width: min(780px, 100%);
  max-height: 86vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 20px;
  background: var(--card);
  box-shadow: 0 30px 80px -30px rgba(15, 18, 40, .55);
}
.wd-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--line);
}
.wd-head .muted {
  font-size: 12px;
  margin-left: 7px;
}
.wd-x {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.wd-x:hover {
  background: var(--bg);
  color: var(--fg);
}
.wd-body {
  padding: 18px 20px;
  overflow-y: auto;
}
.qstem {
  font-size: 15px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}
.opts {
  margin-top: 14px;
}
.opt {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  padding: 8px 11px;
  border: 1px solid var(--line);
  border-radius: 9px;
  margin-bottom: 7px;
  background: var(--card);
  font-size: 13px;
  line-height: 1.6;
}
.opt .k {
  flex: none;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 7px;
  background: var(--bg);
  color: var(--muted);
  font-weight: 700;
  font-size: 11.5px;
}
.opt.ro {
  cursor: default;
}
.opt.ro.right {
  border-color: var(--ok);
  background: var(--ok-soft);
}
.opt.ro.right .k {
  background: var(--ok);
  color: #fff;
}
.answer-line {
  margin-top: 14px;
  font-size: 13.5px;
  color: var(--ok);
}
.answer-line b {
  color: var(--ok);
}
.analysis {
  margin-top: 12px;
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--muted);
  white-space: pre-wrap;
}
.wd-time {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--line);
  font-size: 12px;
}
.wd-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 20px;
  border-top: 1px solid var(--line);
}
@media (max-width: 640px) {
  .wrong-hero {
    align-items: stretch;
    flex-direction: column;
    padding: 18px;
  }
  .hero-top h1 {
    font-size: 26px;
  }
  .wrong-hero p {
    margin-left: 0;
  }
  .wrong-mask {
    padding: 0;
  }
  .wrong-dialog {
    width: 100%;
    max-height: 100vh;
    height: 100vh;
    border-radius: 0;
  }
  .practice-cta {
    width: 100%;
    margin-left: 0;
  }
  .toolbar-total {
    margin-left: 0;
  }
  .src {
    max-width: 100%;
  }
}
</style>
