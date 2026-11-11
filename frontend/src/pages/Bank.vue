<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, TYPE_NAME, type QuestionPage, type QuestionView, type SubjectView } from '../api'
import { toast } from '../toast'
import { confirmDialog } from '../dialog'

const router = useRouter()

const subjects = ref<SubjectView[]>([])
const data = ref<QuestionPage | null>(null)
const loading = ref(true)
const error = ref('')

const subjectId = ref<number | ''>('')
const type = ref('')
const status = ref('')
const keyword = ref('')
const page = ref(1)
const size = 15

const editing = ref<QuestionView | null>(null)
const editAnswer = ref('')
const editAnalysis = ref('')
const saving = ref(false)
const analysisBusy = ref(false)

const dirty = computed(() =>
  !!editing.value &&
  (editAnswer.value !== (editing.value.answer ?? '') ||
    editAnalysis.value !== (editing.value.analysis ?? ''))
)

onMounted(async () => {
  try {
    subjects.value = await api.subjects()
  } catch (e) {
    toast((e as Error).message)
  }
  await load()
  window.addEventListener('keydown', onKey)
})

onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

function onKey(e: KeyboardEvent) {
  // 让 AppDialog 的确认框先处理 Escape，避免一次按键弹出两个确认
  if (e.key === 'Escape' && editing.value && !document.querySelector('.dlg-mask')) {
    e.preventDefault()
    closeEdit()
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await api.questions({
      subjectId: subjectId.value === '' ? undefined : Number(subjectId.value),
      type: type.value || undefined,
      status: status.value || undefined,
      q: keyword.value.trim() || undefined,
      page: page.value,
      size
    })
  } catch (e) {
    data.value = null
    error.value = (e as Error).message
    toast((e as Error).message)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function resetFilters() {
  subjectId.value = ''
  type.value = ''
  status.value = ''
  keyword.value = ''
  search()
}

function go(p: number) {
  const pages = totalPages.value
  if (p < 1 || p > pages) return
  page.value = p
  load()
}

const totalPages = computed(() => {
  if (!data.value) return 1
  return Math.max(1, Math.ceil(data.value.total / data.value.size))
})

function openEdit(q: QuestionView) {
  editing.value = q
  editAnswer.value = q.answer ?? ''
  editAnalysis.value = q.analysis ?? ''
}

async function closeEdit(force = false) {
  if (saving.value) return
  if (!force && editing.value && dirty.value) {
    const ok = await confirmDialog({
      title: '还有修改没有保存',
      message: '关闭后这次修改会丢失。',
      detail: '答案和解析的改动还没有写入题库。',
      confirmText: '放弃修改',
      cancelText: '继续编辑',
      tone: 'warn'
    })
    if (!ok) return
  }
  editing.value = null
  saving.value = false
}

async function save() {
  if (!editing.value) return
  saving.value = true
  try {
    const updated = await api.updateQuestion(editing.value.id, {
      answer: editAnswer.value,
      analysis: editAnalysis.value
    })
    const items = data.value?.items
    if (items) {
      const i = items.findIndex((x) => x.id === updated.id)
      if (i >= 0) items[i] = updated
    }
    editing.value = updated
    editAnswer.value = updated.answer ?? ''
    editAnalysis.value = updated.analysis ?? ''
    toast('已保存')
    await closeEdit()
  } catch (e) {
    toast((e as Error).message)
  } finally {
    saving.value = false
  }
}

async function genAnalysis() {
  if (!editing.value || analysisBusy.value) return
  if (editAnalysis.value.trim()) {
    toast('已经有解析了，不用再生成')
    return
  }
  analysisBusy.value = true
  try {
    const r = await api.aiAnalysis(editing.value.id)
    editAnalysis.value = r.analysis
    const items = data.value?.items
    if (items) {
      const item = items.find((x) => x.id === editing.value!.id)
      if (item) item.analysis = r.analysis
    }
    toast(r.message)
  } catch (e) {
    toast((e as Error).message)
  } finally {
    analysisBusy.value = false
  }
}

async function remove() {
  if (!editing.value) return
  const q = editing.value
  const ok = await confirmDialog({
    title: '删除这道题？',
    message: `${q.stem.slice(0, 60)}…`,
    detail: '这道题的作答记录也会一起删除，删除后不可恢复。',
    confirmText: '删除题目',
    cancelText: '保留',
    tone: 'danger'
  })
  if (!ok) return
  saving.value = true
  try {
    await api.deleteQuestion(q.id)
    toast('已删除')
    await closeEdit(true)
    await load()
  } catch (e) {
    toast((e as Error).message)
  } finally {
    saving.value = false
  }
}

function shortSource(s?: string) {
  if (!s) return ''
  return s.replace(/^\[?\d{5}\]?\s*/, '').replace(/自考|真题|及答案|答案解析|答案/g, '').trim()
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="hello">题库</h1>
        <p class="sub">搜题、看答案和解析，发现补错的答案可以直接改</p>
      </div>
      <button type="button" class="entry-link" @click="router.push('/ai')">
        <span class="e-ico">✦</span>
        用 AI 校验答案
      </button>
    </div>

    <div class="card filters">
      <select v-model="subjectId" class="sel" @change="search">
        <option value="">全部学科</option>
        <option v-for="s in subjects" :key="s.id" :value="s.id">{{ s.name }}</option>
      </select>
      <select v-model="type" class="sel" @change="search">
        <option value="">全部题型</option>
        <option v-for="(name, key) in TYPE_NAME" :key="key" :value="key">{{ name }}</option>
      </select>
      <select v-model="status" class="sel" @change="search">
        <option value="">全部状态</option>
        <option value="ACTIVE">有答案</option>
        <option value="NO_ANSWER">待补答案</option>
      </select>
      <input
        v-model="keyword"
        class="kw"
        placeholder="搜题干 / 答案 / 来源，回车搜索"
        @keyup.enter="search"
      />
      <button class="btn" @click="search">搜索</button>
      <button class="mini" @click="resetFilters">重置</button>
    </div>

    <div v-if="loading" class="card empty">正在查…</div>
    <div v-else-if="error" class="card empty">
      加载失败：{{ error }}
      <div style="margin-top: 12px">
        <button class="btn sm" @click="load">重试</button>
      </div>
    </div>

    <template v-else-if="data">
      <div class="muted total">共 {{ data.total }} 道题 · 第 {{ data.page }} / {{ totalPages }} 页</div>

      <div class="card list">
        <div v-for="q in data.items" :key="q.id" class="row" @click="openEdit(q)">
          <div class="badges">
            <span class="tp">{{ TYPE_NAME[q.type] ?? q.type }}</span>
            <span v-if="q.qno" class="no">第 {{ q.qno }} 题</span>
            <span v-if="q.year" class="yr">{{ q.year }}</span>
          </div>
          <div class="stem">{{ q.stem.replace(/\s+/g, ' ').slice(0, 90) }}</div>
          <div class="tail">
            <span v-if="q.answer" class="ans">{{ q.answer }}</span>
            <span v-else class="noans">无答案</span>
            <span class="src">{{ shortSource(q.source) }}</span>
          </div>
        </div>
        <div v-if="!data.items.length" class="empty">没有符合条件的题</div>
      </div>

      <div class="pager">
        <button class="mini" :disabled="data.page <= 1" @click="go(data.page - 1)">上一页</button>
        <span class="muted">{{ data.page }} / {{ totalPages }}</span>
        <button class="mini" :disabled="data.page >= totalPages" @click="go(data.page + 1)">下一页</button>
      </div>
    </template>

    <!-- 编辑弹层 -->
    <div v-if="editing" class="mask" @click.self="closeEdit()">
      <div class="modal card">
        <div class="modal-head">
          <div>
            <span class="tp">{{ TYPE_NAME[editing.type] ?? editing.type }}</span>
            <span v-if="editing.qno" class="no">第 {{ editing.qno }} 题</span>
            <span v-if="editing.year" class="yr">{{ editing.year }}</span>
            <span class="muted" style="font-size: 12px">{{ shortSource(editing.source) }}</span>
          </div>
          <button class="mini" @click="closeEdit()">关闭</button>
        </div>

        <div class="modal-body">
          <div class="qstem">{{ editing.stem }}</div>
          <div v-if="editing.options?.length" class="opts">
            <div v-for="o in editing.options" :key="o.key" class="opt-line">
              <b>{{ o.key }}</b>
              <span>{{ o.text }}</span>
            </div>
          </div>

          <label class="field">
            <span>答案{{ editing.type === 'MULTIPLE' ? '（多选写在一起，如 ABD）' : '' }}</span>
            <textarea
              v-model="editAnswer"
              class="ta ans-ta"
              rows="1"
              placeholder="留空表示待补答案"
            ></textarea>
          </label>

          <div class="field">
            <div class="field-head">
              <span>解析</span>
              <button
                v-if="!editAnalysis.trim()"
                class="mini"
                :disabled="analysisBusy"
                @click="genAnalysis"
              >
                {{ analysisBusy ? 'AI 生成中…' : 'AI 生成解析' }}
              </button>
              <span v-else class="has-analysis">已有解析</span>
            </div>
            <textarea v-model="editAnalysis" class="ta" rows="4" placeholder="可留空"></textarea>
          </div>
        </div>

        <div class="modal-foot">
          <button class="btn danger" :disabled="saving" @click="remove">删除这题</button>
          <div style="flex: 1"></div>
          <button class="mini" :disabled="saving" @click="closeEdit()">取消</button>
          <button class="btn" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}
.kw {
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--card);
  font-size: 13px;
  font-family: inherit;
  color: inherit;
}
.kw {
  flex: 1;
  min-width: 200px;
}
.kw:focus {
  outline: none;
  border-color: var(--primary);
}
.total {
  font-size: 12.5px;
  margin: 14px 2px 8px;
}
.list {
  padding: 0;
  overflow: hidden;
}
.row {
  display: grid;
  grid-template-columns: 180px 1fr auto;
  gap: 14px;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--line);
  cursor: pointer;
}
.row:last-child {
  border-bottom: none;
}
.row:hover {
  background: var(--primary-soft);
}
.badges {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
  font-size: 11.5px;
}
.tp {
  color: var(--primary);
  background: var(--primary-soft);
  padding: 2px 7px;
  border-radius: 5px;
  white-space: nowrap;
}
.no,
.yr {
  color: var(--muted);
}
.stem {
  font-size: 13.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tail {
  display: flex;
  gap: 10px;
  align-items: center;
  max-width: 260px;
}
.ans {
  font-weight: 700;
  color: var(--ok);
  letter-spacing: 1px;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.noans {
  color: var(--warn);
  font-size: 12px;
  white-space: nowrap;
}
.src {
  font-size: 11.5px;
  color: var(--muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.pager {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: center;
  padding: 16px 0;
  font-size: 13px;
}
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  z-index: 40;
}
.modal {
  width: min(720px, 100%);
  max-height: 88vh;
  display: flex;
  flex-direction: column;
  padding: 16px 18px;
}
.modal-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
}
.modal-head > div {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.modal-body {
  overflow-y: auto;
  padding: 12px 0;
}
.qstem {
  font-size: 15px;
  line-height: 1.75;
  white-space: pre-wrap;
}
.opts {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.opt-line {
  display: flex;
  gap: 10px;
  font-size: 13.5px;
  line-height: 1.6;
}
.opt-line b {
  flex: 0 0 18px;
  color: var(--muted);
}
.field {
  display: block;
  margin-top: 14px;
  font-size: 12.5px;
  color: var(--muted);
}
.field span {
  display: block;
  margin-bottom: 6px;
}
.field-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
}
.field-head span {
  margin: 0;
}
.has-analysis {
  font-size: 11.5px;
  color: var(--muted);
}
.ans-ta {
  min-height: 44px;
  field-sizing: content;
  resize: none;
  line-height: 1.7;
}
.field .ans-ta {
  resize: none;
}
.inp,
.ta {
  width: 100%;
  box-sizing: border-box;
  padding: 9px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  font-size: 14px;
  font-family: inherit;
  color: var(--fg);
  background: var(--card);
}
.ta {
  resize: vertical;
  line-height: 1.6;
}
.inp:focus,
.ta:focus {
  outline: none;
  border-color: var(--primary);
}
.modal-foot {
  display: flex;
  gap: 10px;
  align-items: center;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}
.btn.danger {
  background: var(--bad-soft);
  color: var(--bad);
  border: 1px solid #f5c2c2;
}
@media (max-width: 720px) {
  .row {
    grid-template-columns: 1fr;
    gap: 6px;
  }
  .tail {
    max-width: none;
  }
}
</style>
