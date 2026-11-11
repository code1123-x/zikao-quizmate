<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  api,
  type FsListing,
  type ImportBatchView,
  type ImportHistoryPage,
  type ImportJobView,
  type ImportResult,
  type ScanResult,
  type SubjectView
} from '../api'
import { toast } from '../toast'
import { confirmDialog } from '../dialog'

const router = useRouter()
const route = useRoute()

const JOB_KEY = 'quizmate.importJobId'

const subjects = ref<SubjectView[]>([])
const subjectId = ref<number | null>(null)
const listing = ref<FsListing | null>(null)
const loadingDir = ref(false)
const dirError = ref('')
const scanning = ref(false)
const scan = ref<ScanResult | null>(null)
const result = ref<ImportResult | null>(null)
const resultSubjectId = ref<number | null>(null)
const batches = ref<ImportBatchView[]>([])
const recentHistory = ref<ImportHistoryPage | null>(null)
const allHistory = ref<ImportHistoryPage | null>(null)
const historyLoading = ref(false)
const showHistory = ref(false)
const historyStatus = ref('active')
const historySearch = ref('')
const historyPage = ref(1)
const expandedGroups = ref<Set<string>>(new Set())
const job = ref<ImportJobView | null>(null)
const running = computed(() => job.value?.status === 'RUNNING')
const jobPct = computed(() => {
  const total = job.value?.totalFiles ?? 0
  return total ? Math.min(100, Math.round(((job.value?.doneFiles ?? 0) / total) * 100)) : 0
})

let pollTimer: number | undefined
let pollBusy = false

onMounted(async () => {
  await loadSubjects()
  await openDir()
  const wanted = Number(route.query.subject || 0)
  if (wanted && subjects.value.some((s) => s.id === wanted)) {
    subjectId.value = wanted
  }
  await loadBatches()
  await loadRecentHistory()
  await resumeJob()
})

onBeforeUnmount(() => stopPolling())

function onSubjectChange() {
  // 换学科后旧的预检/导入结果不再可信，必须重新预检
  scan.value = null
  result.value = null
  resultSubjectId.value = null
}

async function loadSubjects() {
  try {
    subjects.value = await api.subjects()
    if (!subjectId.value) {
      const withQuestions = subjects.value.find((s) => s.questionCount > 0)
      subjectId.value = withQuestions?.id ?? subjects.value[0]?.id ?? null
    }
  } catch (e) {
    toast((e as Error).message)
  }
}

async function loadBatches() {
  try {
    const all = await api.importBatches()
    const ids = new Set(subjects.value.map((s) => s.id))
    batches.value = all
      .filter((b) => !b.subjectId || ids.has(b.subjectId))
      // 旧版本留下的“重复导入、0 新增”记录也一并隐藏
      .filter((b) => !(b.status === 'DONE' && b.imported === 0))
  } catch {
    batches.value = []
  }
}

async function loadRecentHistory() {
  historyLoading.value = true
  try {
    recentHistory.value = await api.importHistory({ status: 'active', page: 1, size: 5 })
  } catch {
    recentHistory.value = null
  } finally {
    historyLoading.value = false
  }
}

async function loadAllHistory() {
  historyLoading.value = true
  try {
    allHistory.value = await api.importHistory({
      status: historyStatus.value,
      q: historySearch.value.trim() || undefined,
      page: historyPage.value,
      size: 20
    })
  } catch (e) {
    toast((e as Error).message)
    allHistory.value = null
  } finally {
    historyLoading.value = false
  }
}

function openHistory() {
  historyStatus.value = 'active'
  historySearch.value = ''
  historyPage.value = 1
  showHistory.value = true
  loadAllHistory()
}

function closeHistory() {
  showHistory.value = false
}

function applyHistoryFilter() {
  historyPage.value = 1
  loadAllHistory()
}

function historyGoPage(page: number) {
  const total = allHistory.value?.totalGroups ?? 0
  const pages = Math.max(1, Math.ceil(total / 20))
  if (page < 1 || page > pages) return
  historyPage.value = page
  loadAllHistory()
}

function groupKey(g: { subjectId?: number; path?: string; fileName: string }) {
  return `${g.subjectId ?? 0}|${g.path ?? g.fileName}`
}

function toggleGroup(g: { subjectId?: number; path?: string; fileName: string }) {
  const key = groupKey(g)
  const next = new Set(expandedGroups.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedGroups.value = next
}

function isGroupOpen(g: { subjectId?: number; path?: string; fileName: string }) {
  return expandedGroups.value.has(groupKey(g))
}

function historyTime(s?: string) {
  return s ? s.slice(0, 16).replace('T', ' ') : '—'
}

const HISTORY_TABS = [
  { key: 'active', label: '有新增' },
  { key: 'failed', label: '失败' },
  { key: 'rolled_back', label: '已回滚' },
  { key: 'all', label: '全部' }
]

async function openDir(path?: string) {
  loadingDir.value = true
  dirError.value = ''
  scan.value = null
  result.value = null
  resultSubjectId.value = null
  try {
    listing.value = await api.browseImport(path)
    pickSubjectFromPath()
  } catch (e) {
    dirError.value = (e as Error).message
    toast((e as Error).message)
  } finally {
    loadingDir.value = false
  }
}

/** 按 raw 顶层文件夹名猜学科，比如「4747Java语言程序设计（一）」→ Java语言程序设计（一） */
function pickSubjectFromPath() {
  if (!listing.value) return
  const rel = listing.value.path.slice(listing.value.root.length).replace(/^[\\/]+/, '')
  const top = rel.split(/[\\/]/)[0] ?? ''
  if (!top) return
  const clean = top.replace(/^\s*\d{4,5}\s*/, '')
  const found = subjects.value.find((s) => top.includes(s.name) || s.name.includes(clean))
  // 没匹配到就清空，避免沿用上一个学科误导入
  subjectId.value = found?.id ?? null
}

const relPath = computed(() => {
  if (!listing.value) return ''
  const rel = listing.value.path.slice(listing.value.root.length).replace(/^[\\/]+/, '')
  return rel || '我的资料'
})
// 资料库根目录只用于浏览，不能把整个资料库当成一个学科导入
const atRoot = computed(() => !!listing.value && listing.value.path === listing.value.root)

const supportedHere = computed(
  () => listing.value?.entries.filter((e) => !e.dir && e.supported).length ?? 0
)

async function doScan() {
  if (!subjectId.value) {
    toast('先选学科')
    return
  }
  if (!listing.value) return
  if (atRoot.value) {
    toast('请先进入某个学科/试卷文件夹，不能直接预检账号资料根目录')
    return
  }
  scanning.value = true
  scan.value = null
  result.value = null
  try {
    scan.value = await api.scanImport({ subjectId: subjectId.value, path: listing.value.path })
    if (!scan.value.files) {
      toast('这个文件夹里没有可导入的文档')
    }
  } catch (e) {
    toast((e as Error).message)
  } finally {
    scanning.value = false
  }
}

async function doImport() {
  if (!subjectId.value || !listing.value || running.value) return
  if (atRoot.value) {
    toast('请先进入某个学科/试卷文件夹，不能直接导入账号资料根目录')
    return
  }
  result.value = null
  try {
    const started = await api.startImportJob({
      subjectId: subjectId.value,
      path: listing.value.path
    })
    job.value = started
    localStorage.setItem(JOB_KEY, String(started.id))
    startPolling()
    toast('导入已在后台开始，可以继续浏览，进度会自动刷新')
  } catch (e) {
    toast((e as Error).message)
  }
}

function startPolling() {
  stopPolling()
  pollBusy = false
  pollTimer = window.setInterval(pollJob, 800)
}

function stopPolling() {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

async function pollJob() {
  if (!job.value || pollBusy) return
  pollBusy = true
  try {
    const next = await api.importJob(job.value.id)
    job.value = next
    if (next.status !== 'RUNNING') {
      stopPolling()
      finishJob(next)
    }
  } catch (e) {
    stopPolling()
    toast((e as Error).message)
  } finally {
    pollBusy = false
  }
}

async function resumeJob() {
  const id = Number(localStorage.getItem(JOB_KEY) || 0)
  if (!id) return
  try {
    const current = await api.importJob(id)
    if (current.status === 'RUNNING') {
      job.value = current
      startPolling()
    } else {
      localStorage.removeItem(JOB_KEY)
      if (current.status === 'DONE' || current.imported > 0) {
        result.value = {
          files: current.totalFiles,
          imported: current.imported,
          skipped: current.skipped,
          details: current.details ?? []
        }
        resultSubjectId.value = current.subjectId ?? null
      }
    }
  } catch {
    localStorage.removeItem(JOB_KEY)
  }
}

function finishJob(done: ImportJobView) {
  localStorage.removeItem(JOB_KEY)
  job.value = null
  const details = done.details ?? []
  if (done.status === 'DONE' || done.imported > 0) {
    result.value = {
      files: done.totalFiles,
      imported: done.imported,
      skipped: done.skipped,
      details
    }
    resultSubjectId.value = done.subjectId ?? null
  }
  if (done.status === 'DONE') {
    toast(done.imported > 0
      ? `导入完成：新增 ${done.imported} 题` + (done.pending > 0 ? '，有待补答案的题' : '')
      : (done.message || '没有新增题目'))
  } else if (done.status === 'CANCELLED') {
    toast(`导入已取消，已导入 ${done.imported} 题`)
  } else {
    toast(done.message || '导入失败')
  }
  loadSubjects()
  loadBatches()
  loadRecentHistory()
  if (showHistory.value) loadAllHistory()
}

async function cancelJob() {
  if (!job.value) return
  try {
    const next = await api.cancelImportJob(job.value.id)
    job.value = next
    if (next.status !== 'RUNNING') finishJob(next)
  } catch (e) {
    toast((e as Error).message)
  }
}

const STATUS_TEXT: Record<string, string> = {
  RUNNING: '导入中',
  DONE: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  ROLLED_BACK: '已回滚'
}

function statusText(status: string) {
  return STATUS_TEXT[status] ?? status
}

async function rollback(b: ImportBatchView) {
  const ok = await confirmDialog({
    title: '回滚这次导入？',
    message: `「${b.fileName}」这批导入的 ${b.imported} 道题会被删除。`,
    detail: '这些题的作答记录也会一起删除，删除后不可恢复。',
    confirmText: '确认回滚',
    cancelText: '取消',
    tone: 'danger'
  })
  if (!ok) return
  try {
    const r = await api.rollbackImport(b.id)
    toast(`已删除 ${r.deleted} 道题`)
    await loadSubjects()
    await loadBatches()
    await loadRecentHistory()
    if (showHistory.value) await loadAllHistory()
  } catch (e) {
    toast((e as Error).message)
  }
}

async function rollbackGroup(g: { subjectId?: number; path?: string; fileName: string; imported: number; batchCount: number }) {
  if (!g.subjectId || !g.path) return
  const ok = await confirmDialog({
    title: '删除这个文件夹导入的所有题目？',
    message: `「${g.fileName}」下 ${g.batchCount} 次导入的 ${g.imported} 道题会被删除。`,
    detail: '这些题的作答记录也会一起删除，删除后不可恢复。',
    confirmText: '全部删除',
    cancelText: '取消',
    tone: 'danger'
  })
  if (!ok) return
  try {
    const r = await api.rollbackImportGroup(g.subjectId, g.path)
    toast(`已删除 ${r.deleted} 道题`)
    await loadSubjects()
    await loadBatches()
    await loadRecentHistory()
    if (showHistory.value) await loadAllHistory()
  } catch (e) {
    toast((e as Error).message)
  }
}

function sizeText(n: number) {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

const scanPending = computed(() => scan.value?.pending ?? 0)
const scanDuplicates = computed(() => scan.value?.details.reduce((a, d) => a + (d.duplicates ?? 0), 0) ?? 0)
const resultPending = computed(() => result.value?.details.reduce((a, d) => a + d.pending, 0) ?? 0)
const selectedSubjectName = computed(
  () => subjects.value.find((s) => s.id === subjectId.value)?.name ?? '未选择'
)
const fillSubjectId = computed(() => resultSubjectId.value ?? subjectId.value)
const alreadyImported = computed(() => {
  if (!listing.value || !subjectId.value) return null
  return batches.value.find((b) =>
    b.subjectId === subjectId.value &&
    b.filePath === listing.value!.path &&
    b.imported > 0
  ) ?? null
})
</script>

<template>
  <div class="page">
    <h1 class="hello">导入题库</h1>
    <p class="sub">从我的资料里选文件夹，先预检再入库；导错了可以回滚</p>

    <div class="card">
      <div class="row-line">
        <span class="label">学科</span>
        <select v-model="subjectId" class="sel" :disabled="running" @change="onSubjectChange">
          <option v-for="s in subjects" :key="s.id" :value="s.id">
            {{ s.name }}{{ s.questionCount > 0 ? `（已导入 ${s.questionCount} 题）` : '（未导入）' }}
          </option>
        </select>
        <span v-if="!subjects.length" class="muted" style="font-size: 12.5px">
          当前专业还没有学科，请先去「考试计划」添加本次要考的学科
        </span>
      </div>
    </div>

    <div class="sec-title"><span>选文件夹</span></div>
    <div class="card">
      <div class="crumbs">
        <button v-if="listing?.parent" class="mini" @click="openDir(listing.parent)">↑ 上一级</button>
        <span class="path">{{ relPath }}</span>
      </div>
      <div v-if="atRoot" class="root-tip">
        这里是你的资料库根目录，只用于浏览。请进入某个学科/试卷文件夹，再预检或导入，避免把不同学科的资料混到一起。
      </div>

      <div v-if="loadingDir" class="muted" style="padding: 10px 0">正在读取目录…</div>
      <div v-else-if="dirError" class="muted" style="padding: 10px 0">
        目录读取失败：{{ dirError }}
        <button class="mini" style="margin-left: 8px" @click="openDir()">重试</button>
      </div>
      <div v-else class="entries">
        <button
          v-for="e in listing?.entries ?? []"
          :key="e.path"
          class="entry"
          :class="{ dir: e.dir, off: !e.dir && !e.supported }"
          :disabled="!e.dir && !e.supported"
          @click="e.dir && openDir(e.path)"
        >
          <span class="ico">{{ e.dir ? '📁' : e.supported ? '📄' : '·' }}</span>
          <span class="name">{{ e.name }}</span>
          <span class="meta">{{ e.dir ? '' : sizeText(e.size) }}</span>
        </button>
        <div v-if="!(listing?.entries.length)" class="muted" style="padding: 8px 0">空文件夹</div>
      </div>

      <div class="actions">
        <span class="muted" style="font-size: 12.5px">
          当前文件夹里可直接解析 {{ supportedHere }} 个文档（会包含子文件夹）
        </span>
        <button class="btn" :disabled="scanning || running || !subjectId || atRoot" @click="doScan">
          {{ scanning ? '正在预检…' : '预检这个文件夹' }}
        </button>
      </div>
    </div>

    <template v-if="scan">
      <div class="sec-title"><span>预检结果</span></div>
      <div class="card">
        <div class="stats">
          <div class="s"><b>{{ scan.files }}</b><span>文档</span></div>
          <div class="s"><b>{{ scan.parsed }}</b><span>解析出题</span></div>
          <div class="s ok"><b>{{ scan.withAnswer }}</b><span>可直接导入</span></div>
          <div class="s warn"><b>{{ scan.pending }}</b><span>待补答案</span></div>
          <div class="s warn"><b>{{ scan.skipped }}</b><span>结构异常/跳过</span></div>
          <div class="s"><b>{{ scanDuplicates }}</b><span>疑似重复题</span></div>
          <div class="s"><b>{{ scan.scannedFiles }}</b><span>扫描件跳过</span></div>
        </div>

        <div class="files">
          <div v-for="f in scan.details" :key="f.path" class="frow">
            <div class="fname">{{ f.name }}</div>
            <div class="fmeta">
              <span v-if="f.parsed">解析 {{ f.parsed }}</span>
              <span v-if="f.withAnswer" class="ok">可导入 {{ f.withAnswer }}</span>
              <span v-if="f.pending" class="warn">待补 {{ f.pending }}</span>
              <span v-if="f.skipped" class="muted">跳过 {{ f.skipped }}</span>
              <span v-if="f.duplicates" class="warn">重复 {{ f.duplicates }}</span>
              <span class="muted">{{ f.message }}</span>
            </div>
            <div v-if="f.samples.length" class="samples">
              <div v-for="(s, i) in f.samples" :key="i">{{ s }}</div>
            </div>
          </div>
        </div>

        <div class="actions">
          <span class="muted" style="font-size: 12.5px">
            将导入到「{{ selectedSubjectName }}」：会导入 {{ scan.withAnswer }} 道有答案的题
            <template v-if="scanPending">，另有 {{ scanPending }} 道以待补答案状态入库</template>
            <template v-if="scan.skipped">；{{ scan.skipped }} 道题因结构异常或答案不合法，不会导入</template>
            <template v-if="scan.scannedFiles">；{{ scan.scannedFiles }} 个扫描件没有文字层，需要 OCR 或转成可解析文档</template>
            <template v-if="alreadyImported">
              <br />这个文件夹之前已经导入过（新增 {{ alreadyImported.imported }} 题）；再次导入只会补充新题，重复题会被跳过，不会新增历史记录。
            </template>
          </span>
          <button class="btn" :disabled="running" @click="doImport">
            {{ running ? '正在导入…' : '开始导入' }}
          </button>
        </div>
      </div>
    </template>

    <template v-if="job">
      <div class="sec-title"><span>后台导入中</span></div>
      <div class="card job-card">
        <div class="job-head">
          <b>正在处理 {{ job.doneFiles }} / {{ job.totalFiles }} 个文件</b>
          <span class="job-pct">{{ jobPct }}%</span>
        </div>
        <div class="job-bar"><i :style="{ width: jobPct + '%' }"></i></div>
        <div class="job-meta">
          <span>新增 {{ job.imported }} 题</span>
          <span>跳过 {{ job.skipped }} 题</span>
          <span v-if="job.pending">待补 {{ job.pending }} 题</span>
          <span v-if="job.currentFile">当前：{{ job.currentFile }}</span>
        </div>
        <div class="job-actions">
          <span class="muted" style="font-size: 12.5px">{{ job.message }}</span>
          <button class="mini danger" :disabled="job.status !== 'RUNNING'" @click="cancelJob">取消导入</button>
        </div>
      </div>
    </template>

    <template v-if="result">
      <div class="sec-title"><span>导入结果</span></div>
      <div class="card">
        <div class="stats">
          <div class="s"><b>{{ result.imported }}</b><span>新增题目</span></div>
          <div class="s"><b>{{ result.skipped }}</b><span>去重/跳过</span></div>
          <div class="s warn"><b>{{ resultPending }}</b><span>待补答案</span></div>
        </div>

        <div class="files">
          <div v-for="f in result.details" :key="f.file" class="frow">
            <div class="fname">{{ f.file }}</div>
            <div class="fmeta">
              <span :class="f.status === 'OK' ? 'ok' : 'muted'">{{ f.status }}</span>
              <span v-if="f.imported">新增 {{ f.imported }}</span>
              <span v-if="f.skipped" class="muted">跳过 {{ f.skipped }}</span>
              <span v-if="f.pending" class="warn">待补 {{ f.pending }}</span>
              <span class="muted">{{ f.message }}</span>
            </div>
          </div>
        </div>

        <div class="actions">
          <template v-if="resultPending && fillSubjectId">
            <button class="btn" @click="router.push(`/fill/${fillSubjectId}`)">去补答案（{{ resultPending }}）</button>
          </template>
          <button class="btn ghost" @click="result = null; scan = null">继续导入</button>
        </div>
      </div>
    </template>

    <div class="sec-title history-title">
      <span>导入历史</span>
      <span v-if="recentHistory" class="muted history-count">
        最近 5 条 · 共 {{ recentHistory.totalGroups }} 个文件夹 / {{ recentHistory.totalBatches }} 次导入
      </span>
      <button v-if="recentHistory?.totalGroups" class="mini" @click="openHistory">查看全部</button>
    </div>
    <div class="card">
      <div v-if="historyLoading && !recentHistory" class="muted" style="font-size: 13px">正在读取导入历史…</div>
      <div v-else-if="!recentHistory?.groups.length" class="muted" style="font-size: 13px">还没有导入记录</div>
      <div v-for="g in recentHistory?.groups ?? []" :key="groupKey(g)" class="hgroup">
        <div class="hgroup-head" @click="toggleGroup(g)">
          <div class="hgroup-main">
            <div class="hname">{{ g.fileName }}</div>
            <div class="hmeta">
              <span>{{ g.subjectName || '—' }}</span>
              <span>{{ historyTime(g.latestAt) }}</span>
              <span>{{ g.batchCount }} 次导入</span>
              <span>累计新增 {{ g.imported }} 题</span>
              <span>{{ statusText(g.latestStatus) }}</span>
            </div>
          </div>
          <div class="hgroup-actions" @click.stop>
            <button
              v-if="g.imported > 0 && g.latestStatus !== 'ROLLED_BACK'"
              class="mini danger"
              :disabled="g.latestStatus === 'RUNNING'"
              @click="rollbackGroup(g)"
            >
              删除全部
            </button>
            <button class="mini" @click="toggleGroup(g)">{{ isGroupOpen(g) ? '收起' : '展开' }}</button>
          </div>
        </div>
        <div v-if="isGroupOpen(g)" class="hbatch-list">
          <div v-for="b in g.batches" :key="b.id" class="hbatch">
            <span class="hbdate">{{ historyTime(b.createdAt) }}</span>
            <span>{{ statusText(b.status) }}</span>
            <span>新增 {{ b.imported }}</span>
            <span class="muted" v-if="b.skipped">跳过 {{ b.skipped }}</span>
            <button
              class="mini danger"
              :disabled="b.status === 'RUNNING' || b.status === 'ROLLED_BACK' || b.imported === 0"
              @click="rollback(b)"
            >
              {{ b.status === 'ROLLED_BACK' ? '已回滚' : '撤销这批' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="showHistory" class="history-mask" @click.self="closeHistory">
      <div class="history-dialog">
        <header class="history-head">
          <div>
            <h3>全部导入历史</h3>
            <span v-if="allHistory">
              共 {{ allHistory.totalGroups }} 个文件夹 / {{ allHistory.totalBatches }} 次导入
            </span>
          </div>
          <button class="history-x" title="关闭" @click="closeHistory">✕</button>
        </header>

        <div class="history-filters">
          <div class="history-tabs">
            <button
              v-for="tab in HISTORY_TABS"
              :key="tab.key"
              class="history-tab"
              :class="{ on: historyStatus === tab.key }"
              @click="historyStatus = tab.key; applyHistoryFilter()"
            >
              {{ tab.label }}
            </button>
          </div>
          <div class="history-search">
            <input
              v-model="historySearch"
              class="inp"
              placeholder="搜索学科 / 文件夹"
              @keyup.enter="applyHistoryFilter"
            />
            <button class="mini" @click="applyHistoryFilter">搜索</button>
          </div>
        </div>

        <div class="history-body">
          <div v-if="historyLoading" class="muted" style="padding: 24px 0; text-align: center">正在读取…</div>
          <div v-else-if="!allHistory?.groups.length" class="muted" style="padding: 24px 0; text-align: center">
            没有符合条件的导入记录
          </div>
          <div v-for="g in allHistory?.groups ?? []" :key="groupKey(g)" class="hgroup">
            <div class="hgroup-head" @click="toggleGroup(g)">
              <div class="hgroup-main">
                <div class="hname">{{ g.fileName }}</div>
                <div class="hmeta">
                  <span>{{ g.subjectName || '—' }}</span>
                  <span>{{ historyTime(g.latestAt) }}</span>
                  <span>{{ g.batchCount }} 次导入</span>
                  <span>累计新增 {{ g.imported }} 题</span>
                  <span>{{ statusText(g.latestStatus) }}</span>
                </div>
              </div>
              <div class="hgroup-actions" @click.stop>
                <button
                  v-if="g.imported > 0 && g.latestStatus !== 'ROLLED_BACK'"
                  class="mini danger"
                  :disabled="g.latestStatus === 'RUNNING'"
                  @click="rollbackGroup(g)"
                >
                  删除全部
                </button>
                <button class="mini" @click="toggleGroup(g)">{{ isGroupOpen(g) ? '收起' : '展开' }}</button>
              </div>
            </div>
            <div v-if="isGroupOpen(g)" class="hbatch-list">
              <div v-for="b in g.batches" :key="b.id" class="hbatch">
                <span class="hbdate">{{ historyTime(b.createdAt) }}</span>
                <span>{{ statusText(b.status) }}</span>
                <span>新增 {{ b.imported }}</span>
                <span class="muted" v-if="b.skipped">跳过 {{ b.skipped }}</span>
                <button
                  class="mini danger"
                  :disabled="b.status === 'RUNNING' || b.status === 'ROLLED_BACK' || b.imported === 0"
                  @click="rollback(b)"
                >
                  {{ b.status === 'ROLLED_BACK' ? '已回滚' : '撤销这批' }}
                </button>
              </div>
            </div>
          </div>
        </div>

        <footer class="history-foot">
          <button class="mini" :disabled="historyPage <= 1" @click="historyGoPage(historyPage - 1)">上一页</button>
          <span class="muted">{{ historyPage }} / {{ Math.max(1, Math.ceil((allHistory?.totalGroups ?? 0) / 20)) }}</span>
          <button
            class="mini"
            :disabled="historyPage >= Math.max(1, Math.ceil((allHistory?.totalGroups ?? 0) / 20))"
            @click="historyGoPage(historyPage + 1)"
          >
            下一页
          </button>
        </footer>
      </div>
    </div>
  </div>
</template>

<style scoped>
.card {
  padding: 16px;
  margin-bottom: 14px;
}
.row-line {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
.label {
  font-size: 13px;
  color: var(--muted);
}
.sel {
  min-width: 220px;
  max-width: 380px;
}
.crumbs {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.path {
  font-size: 12.5px;
  color: var(--muted);
  word-break: break-all;
}
.root-tip {
  margin-bottom: 10px;
  padding: 8px 11px;
  border-radius: 9px;
  background: var(--warn-soft);
  color: var(--warn);
  font-size: 12.5px;
  line-height: 1.6;
}
.entries {
  border: 1px solid var(--line);
  border-radius: 10px;
  max-height: 320px;
  overflow-y: auto;
}
.entry {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 8px 12px;
  border: none;
  border-bottom: 1px solid var(--line);
  background: transparent;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.entry:last-child {
  border-bottom: none;
}
.entry.dir:hover {
  background: var(--primary-soft);
}
.entry.off {
  color: var(--muted);
  cursor: default;
}
.entry .name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.entry .meta {
  font-size: 11.5px;
  color: var(--muted);
}
.actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 14px;
}
.stats {
  display: flex;
  gap: 22px;
  flex-wrap: wrap;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line);
}
.s {
  display: flex;
  flex-direction: column;
}
.s b {
  font-size: 20px;
}
.s span {
  font-size: 12px;
  color: var(--muted);
}
.s.ok b {
  color: var(--ok);
}
.s.warn b {
  color: var(--warn);
}
.files {
  max-height: 340px;
  overflow-y: auto;
  padding: 8px 0;
}
.frow {
  padding: 8px 0;
  border-bottom: 1px dashed var(--line);
  font-size: 12.5px;
}
.frow:last-child {
  border-bottom: none;
}
.fname {
  font-weight: 600;
  margin-bottom: 3px;
  word-break: break-all;
}
.fmeta {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  color: var(--muted);
}
.ok {
  color: var(--ok);
}
.warn {
  color: var(--warn);
}
.samples {
  margin-top: 5px;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.7;
}
.brow {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--line);
}
.brow:last-child {
  border-bottom: none;
}
.bmain {
  min-width: 0;
}
.bname {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bmeta {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 11.5px;
  color: var(--muted);
  margin-top: 2px;
}
.mini.danger {
  color: var(--bad);
  border-color: #f5c2c2;
  background: var(--bad-soft);
}
.mini.danger:disabled {
  opacity: 0.45;
}
.job-card {
  border-color: color-mix(in srgb, var(--primary) 25%, var(--line));
  background: linear-gradient(180deg, var(--primary-soft), var(--card) 60%);
}
.job-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  font-size: 13.5px;
}
.job-pct {
  color: var(--primary);
  font-weight: 700;
}
.job-bar {
  height: 8px;
  margin: 10px 0;
  border-radius: 999px;
  background: var(--line);
  overflow: hidden;
}
.job-bar i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #6b6bee, #5b5be0);
  transition: width 0.25s ease;
}
.job-meta {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--muted);
}
.job-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 12px;
}
.history-title {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.history-count {
  font-size: 12px;
  font-weight: 400;
}
.history-title .mini {
  margin-left: auto;
}
.hgroup {
  padding: 8px 0;
  border-bottom: 1px dashed var(--line);
}
.hgroup:last-child {
  border-bottom: none;
}
.hgroup-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  cursor: pointer;
}
.hgroup-main {
  min-width: 0;
}
.hname {
  font-size: 13.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hmeta {
  display: flex;
  gap: 9px;
  flex-wrap: wrap;
  margin-top: 3px;
  color: var(--muted);
  font-size: 11.5px;
}
.hgroup-actions {
  display: flex;
  gap: 6px;
  flex: none;
}
.hbatch-list {
  margin: 8px 0 4px 14px;
  padding-left: 12px;
  border-left: 2px solid var(--line);
}
.hbatch {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 5px 0;
  font-size: 12px;
  color: var(--muted);
}
.hbatch .mini {
  margin-left: auto;
}
.hbdate {
  min-width: 108px;
  color: var(--fg);
}
.history-mask {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(15, 18, 40, .42);
  backdrop-filter: blur(3px);
}
.history-dialog {
  width: min(860px, 100%);
  max-height: 86vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 20px;
  background: var(--card);
  box-shadow: 0 30px 80px -30px rgba(15, 18, 40, .55);
}
.history-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 20px 12px;
  border-bottom: 1px solid var(--line);
}
.history-head h3 {
  margin: 0 0 3px;
  font-size: 17px;
}
.history-head span {
  font-size: 12px;
  color: var(--muted);
}
.history-x {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.history-x:hover {
  background: var(--bg);
  color: var(--fg);
}
.history-filters {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  padding: 12px 20px;
  border-bottom: 1px solid var(--line);
}
.history-tabs {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.history-tab {
  padding: 6px 11px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--card);
  color: var(--muted);
  font-family: inherit;
  font-size: 12px;
}
.history-tab.on {
  color: var(--primary);
  border-color: var(--primary);
  background: var(--primary-soft);
}
.history-search {
  display: flex;
  gap: 6px;
  align-items: center;
}
.history-search .inp {
  width: 200px;
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--card);
  color: var(--fg);
  font-family: inherit;
  font-size: 12.5px;
}
.history-body {
  padding: 6px 20px 12px;
  overflow-y: auto;
}
.history-foot {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 12px 20px;
  border-top: 1px solid var(--line);
}
.history-foot .muted {
  font-size: 12px;
}
@media (max-width: 640px) {
  .history-mask {
    padding: 0;
  }
  .history-dialog {
    max-height: 100vh;
    height: 100vh;
    border-radius: 0;
  }
  .history-search .inp {
    width: 100%;
  }
  .history-search {
    width: 100%;
  }
  .hgroup-head {
    align-items: flex-start;
  }
  .hgroup-actions {
    flex-direction: column;
  }

  .stats {
    gap: 14px;
  }
  .sel {
    min-width: 0;
    flex: 1;
  }
}
</style>
