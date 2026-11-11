<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  api,
  TYPE_NAME,
  type AiCheckView,
  type AiConfigView,
  type AiProgress,
  type AiSubjectStat,
  type SubjectView
} from '../api'
import { toast } from '../toast'
import { confirmDialog } from '../dialog'

const router = useRouter()

const cfg = ref<AiConfigView | null>(null)

const subjects = ref<SubjectView[]>([])
const subjectId = ref<number | null>(null)
const limit = ref(20)
const starting = ref(false)
const stats = ref<Record<number, AiSubjectStat>>({})

const progress = ref<AiProgress | null>(null)
const checks = ref<AiCheckView[]>([])
const statusFilter = ref('NEW')
const loadingChecks = ref(false)
const applying = ref<number | null>(null)
const applyingAll = ref(false)

let timer: number | undefined

onMounted(async () => {
  await Promise.all([loadConfig(), loadSubjects(), loadStats()])
  await loadChecks()
})

onBeforeUnmount(() => {
  window.clearInterval(timer)
})

// 切换学科时自动刷新校验结果
watch(subjectId, () => {
  if (subjectId.value != null) loadChecks()
})

async function loadConfig() {
  try {
    cfg.value = await api.aiConfig()
  } catch (e) {
    toast((e as Error).message)
  }
}

async function loadSubjects() {
  try {
    subjects.value = (await api.subjects()).filter((s) => s.choiceCount > 0)
    subjectId.value = subjects.value[0]?.id ?? null
  } catch (e) {
    toast((e as Error).message)
  }
}

async function loadStats() {
  try {
    const list = await api.aiCheckStats()
    const m: Record<number, AiSubjectStat> = {}
    for (const s of list) m[s.subjectId] = s
    stats.value = m
  } catch {
    // 统计失败不影响校验本身
  }
}

const currentStat = computed(() =>
  subjectId.value != null ? stats.value[subjectId.value] : undefined
)

async function startChecks() {
  if (!subjectId.value) return
  starting.value = true
  try {
    const r = await api.startAiChecks({
      subjectId: subjectId.value,
      limit: limit.value,
      onlyUnchecked: false
    })
    toast(r.message)
    startPolling()
  } catch (e) {
    toast((e as Error).message)
  } finally {
    starting.value = false
  }
}

function startPolling() {
  window.clearInterval(timer)
  timer = window.setInterval(async () => {
    try {
      const p = await api.aiProgress()
      progress.value = p
      if (!p.running) {
        window.clearInterval(timer)
        toast(p.message)
        await loadChecks()
        await loadStats()
      }
    } catch {
      window.clearInterval(timer)
    }
  }, 1500)
}

let checksSeq = 0

async function loadChecks() {
  const seq = ++checksSeq
  const sid = subjectId.value
  const status = statusFilter.value
  loadingChecks.value = true
  try {
    const list = await api.aiChecks(sid ?? undefined, status)
    // 快速切换学科/标签时，只保留最后一次请求的结果
    if (seq === checksSeq) checks.value = list
  } catch (e) {
    if (seq === checksSeq) toast((e as Error).message)
  } finally {
    if (seq === checksSeq) loadingChecks.value = false
  }
}

async function apply(item: AiCheckView) {
  applying.value = item.questionId
  try {
    await api.applyAi(item.questionId)
    toast('已按 AI 答案更新')
    await loadChecks()
  } catch (e) {
    toast((e as Error).message)
  } finally {
    applying.value = null
  }
}

async function ignore(item: AiCheckView) {
  try {
    await api.ignoreAi(item.questionId)
    checks.value = checks.value.filter((c) => c.questionId !== item.questionId)
  } catch (e) {
    toast((e as Error).message)
  }
}

async function openApplyAll() {
  if (!checks.value.length || applyingAll.value) return
  const ok = await confirmDialog({
    title: '一键采纳 AI 答案',
    message: `把当前列表里的 ${checks.value.length} 道题改成 AI 给出的答案。`,
    detail: '只会处理当前列表里「答案不一致」的题；采纳后可在「已采纳」里查看。',
    confirmText: `确认采纳 ${checks.value.length} 条`,
    cancelText: '取消'
  })
  if (!ok) return
  await applyAllChecks()
}

async function applyAllChecks() {
  if (!checks.value.length || applyingAll.value) return
  applyingAll.value = true
  try {
    const ids = checks.value.map((c) => c.questionId)
    const r = await api.applyAllAi(ids, subjectId.value ?? undefined)
    toast(r.message)
    await loadChecks()
  } catch (e) {
    toast((e as Error).message)
  } finally {
    applyingAll.value = false
  }
}

const progressPct = computed(() => {
  const p = progress.value
  if (!p || !p.total) return 0
  return Math.round((p.done / p.total) * 100)
})
</script>

<template>
  <div class="page">
    <h1 class="hello">AI 校验答案</h1>
    <p class="sub">
      让 AI 独立做一遍题，和题库答案比对；没答案的题也会给出建议答案，算作不一致。结果只显示不一致的题，采纳后才写入题库
    </p>

    <div class="ai-status" :class="cfg?.hasKey ? 'ok' : 'off'">
      {{ cfg?.hasKey ? `已配置：${cfg.model}` : '未配置 API Key —— 去「设置 → AI 接口」配置后这里会变成「已配置」' }}
    </div>

    <div class="card config-tip">
      <div>
        <b>接口地址、模型和 API Key</b>
        <span>统一在「设置 → AI 接口」里配置和测试连接。</span>
      </div>
      <button class="btn ghost" @click="router.push('/settings')">去设置</button>
    </div>

    <!-- 开始校验 -->
    <div class="sec-title"><span>开始校验</span></div>
    <div class="card">
      <div class="form row">
        <label class="field">
          <span>学科</span>
          <select v-model="subjectId" class="sel">
            <option v-for="s in subjects" :key="s.id" :value="s.id">
              {{ s.name }}（共 {{ stats[s.id]?.total ?? s.choiceCount }} 题）
            </option>
          </select>
        </label>
        <label class="field">
          <span>本次校验题数</span>
          <input v-model.number="limit" class="inp" type="number" min="1" max="200" />
        </label>
      </div>

      <div v-if="currentStat" class="stat-line">
        <template v-if="currentStat.unchecked > 0">
          共 {{ currentStat.total }} 题，已校验 {{ currentStat.total - currentStat.unchecked }} 题，还剩
          <b>{{ currentStat.unchecked }}</b> 题未 AI 校验
        </template>
        <template v-else>
          共 {{ currentStat.total }} 题，<b class="all-done">已全部 AI 校验</b>
          <span class="redo-tip">（再跑会从最早校验过的题开始复查）</span>
        </template>
      </div>

      <div class="actions">
        <button class="btn" :disabled="starting || progress?.running" @click="startChecks">
          {{ progress?.running ? '校验中…' : '开始校验' }}
        </button>
      </div>

      <div v-if="progress && (progress.running || progress.total)" class="progress">
        <div class="pbar"><i :style="{ width: progressPct + '%' }"></i></div>
        <div class="ptext">
          {{ progress.message }}
          <span v-if="progress.total">（{{ progress.done }}/{{ progress.total }}）</span>
        </div>
      </div>
    </div>

    <!-- 结果 -->
    <div class="sec-title">
      <span>校验结果</span>
      <div class="head-actions">
        <button
          v-if="statusFilter === 'NEW' && checks.length"
          class="mini adopt"
          :disabled="applyingAll"
          @click="openApplyAll"
        >
          {{ applyingAll ? '采纳中…' : `一键采纳 ${checks.length} 条 AI 答案` }}
        </button>
        <div class="tabs">
          <button class="tab" :class="{ on: statusFilter === 'NEW' }" @click="statusFilter = 'NEW'; loadChecks()">待处理</button>
          <button class="tab" :class="{ on: statusFilter === 'APPLIED' }" @click="statusFilter = 'APPLIED'; loadChecks()">已采纳</button>
          <button class="tab" :class="{ on: statusFilter === 'IGNORED' }" @click="statusFilter = 'IGNORED'; loadChecks()">已忽略</button>
        </div>
      </div>
    </div>

    <div class="card">
      <div v-if="loadingChecks" class="muted" style="font-size: 13px">正在加载…</div>
      <div v-else-if="!checks.length" class="muted" style="font-size: 13px">
        <template v-if="statusFilter === 'NEW'">
          没有答案不一致的题。校验结果里只保留和题库答案不一致的题，一致的不会出现在这里。
        </template>
        <template v-else-if="statusFilter === 'APPLIED'">还没有采纳过 AI 答案。</template>
        <template v-else>还没有忽略过记录。</template>
      </div>
      <template v-else>
        <div class="summary">
          <template v-if="statusFilter === 'NEW'">共 {{ checks.length }} 条答案不一致，等待处理</template>
          <template v-else-if="statusFilter === 'APPLIED'">共 {{ checks.length }} 条已采纳的 AI 答案</template>
          <template v-else>共 {{ checks.length }} 条已忽略</template>
        </div>

        <div v-for="c in checks" :key="c.questionId" class="row-item" :class="c.verdict.toLowerCase()">
          <div class="rhead">
            <span class="pill">{{ TYPE_NAME[c.type] ?? c.type }}</span>
            <span class="verdict" :class="c.verdict.toLowerCase()">不一致</span>
            <span class="muted" style="font-size: 11.5px">{{ c.model }}</span>
          </div>
          <div class="stem">{{ c.stem.replace(/\s+/g, ' ').slice(0, 110) }}</div>
          <div class="answers">
            <span>题库 <b class="mine">{{ c.answer || '无答案' }}</b></span>
            <span>AI <b class="ai">{{ c.aiAnswer || '—' }}</b></span>
            <span v-if="c.reason" class="muted reason">{{ c.reason }}</span>
          </div>
          <div class="row-actions">
            <button class="mini" :disabled="!c.aiAnswer || applying === c.questionId" @click="apply(c)">
              {{ applying === c.questionId ? '更新中…' : '采纳 AI 答案' }}
            </button>
            <button class="mini" @click="ignore(c)">忽略</button>
            <button class="mini" @click="router.push('/bank')">去题库看</button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.card {
  padding: 18px;
  margin-bottom: 14px;
}
.ai-status {
  display: inline-block;
  margin: 0 0 18px;
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 12.5px;
  font-weight: 600;
}
.ai-status.ok {
  color: var(--ok);
  background: var(--ok-soft);
}
.config-tip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.config-tip b {
  display: block;
  font-size: 13.5px;
}
.config-tip span {
  display: block;
  margin-top: 3px;
  font-size: 12.5px;
  color: var(--muted);
}
.ai-status.off {
  color: var(--warn);
  background: var(--warn-soft);
}
.chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.chip {
  padding: 7px 13px;
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
.form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.field .sel {
  width: 100%;
}
.config-form {
  grid-template-columns: 1.1fr 0.9fr 1.4fr;
}
@media (max-width: 900px) {
  .config-form {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 640px) {
  .form {
    grid-template-columns: 1fr;
  }
}
.form.row {
  align-items: end;
}
.field span {
  display: block;
  font-size: 12.5px;
  color: var(--muted);
  margin-bottom: 6px;
}
.inp {
  width: 100%;
  box-sizing: border-box;
  padding: 9px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  font-size: 13.5px;
  font-family: inherit;
  color: var(--fg);
  background: var(--card);
}
.inp:focus {
  outline: none;
  border-color: var(--primary);
}
.check {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--muted);
  padding-bottom: 9px;
}
.check input {
  accent-color: var(--primary);
}
.think-toggle {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  margin-top: 14px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
  font-size: 12.5px;
  color: var(--muted);
  line-height: 1.65;
  cursor: pointer;
}
.think-toggle input {
  margin-top: 3px;
  accent-color: var(--primary);
}
.think-toggle b {
  color: var(--fg);
  margin-right: 4px;
}
.stat-line {
  margin-top: 12px;
  font-size: 12.5px;
  color: var(--muted);
}
.stat-line b {
  color: var(--primary);
  font-size: 13.5px;
}
.stat-line b.all-done {
  color: var(--ok);
}
.stat-line .redo-tip {
  font-size: 12px;
  color: var(--muted);
}
.hint {
  margin-top: 12px;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.8;
}
.hint code {
  background: var(--bg);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 11.5px;
}
.actions {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 14px;
}
.progress {
  margin-top: 16px;
}
.pbar {
  height: 7px;
  border-radius: 999px;
  background: var(--line);
  overflow: hidden;
}
.pbar i {
  display: block;
  height: 100%;
  background: var(--primary);
  transition: width .3s;
}
.ptext {
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--muted);
}
.tabs {
  display: flex;
  gap: 6px;
}
.tab {
  padding: 5px 12px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: var(--card);
  font-size: 12.5px;
}
.tab.on {
  border-color: var(--primary);
  background: var(--primary);
  color: #fff;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.adopt {
  border-color: var(--primary);
  background: var(--primary);
  color: #fff;
  font-weight: 600;
  padding: 6px 14px;
}
.adopt:disabled {
  opacity: .6;
}
.summary {
  font-size: 12.5px;
  color: var(--muted);
  margin-bottom: 10px;
}
.row-item {
  padding: 12px 0;
  border-bottom: 1px solid var(--line);
}
.row-item:last-child {
  border-bottom: none;
}
.rhead {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
}
.pill {
  background: var(--primary-soft);
  color: var(--primary);
  border-radius: 99px;
  padding: 2px 9px;
  font-size: 11.5px;
  font-weight: 600;
}
.verdict {
  font-size: 11.5px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 99px;
}
.verdict.match {
  color: var(--ok);
  background: var(--ok-soft);
}
.verdict.diff {
  color: var(--bad);
  background: var(--bad-soft);
}
.verdict.error {
  color: var(--warn);
  background: var(--warn-soft);
}
.stem {
  font-size: 13.5px;
  line-height: 1.7;
}
.answers {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  align-items: baseline;
  font-size: 12.5px;
  color: var(--muted);
  margin-top: 6px;
}
.answers b {
  font-size: 14px;
  letter-spacing: .06em;
}
.mine {
  color: var(--fg);
}
.ai {
  color: var(--primary);
}
.reason {
  max-width: 60ch;
}
.row-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  flex-wrap: wrap;
}
@media (max-width: 640px) {
  .form {
    grid-template-columns: 1fr;
  }
}
</style>
