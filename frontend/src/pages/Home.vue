<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, type ExamPlan, type Overview, type SubjectView } from '../api'
import { toast } from '../toast'
import { confirmDialog, promptDialog } from '../dialog'
import { practiceSettings, savePracticeSettings, type PracticeSettings } from '../settings'
import PracticeSettingsDialog from '../components/PracticeSettingsDialog.vue'

const router = useRouter()
const subjects = ref<SubjectView[]>([])
const overview = ref<Overview | null>(null)
const plan = ref<ExamPlan | null>(null)
const loading = ref(true)
const loadError = ref('')
const starting = ref(false)
const deleting = ref<number | null>(null)
const showSettings = ref(false)

const goal = computed(() => practiceSettings.value.goal)
const practiceCount = computed(() => practiceSettings.value.count)

const activeSubjects = computed(() => subjects.value.filter((s) => s.questionCount > 0))
// 只有已经加入本次考试计划、但还没有题目的学科才显示在「还没题目的学科」里
const idleSubjects = computed(() =>
  subjects.value.filter((s) => s.questionCount === 0 && !!s.examDate)
)

const nextExamSubjects = computed(() => {
  if (!plan.value?.nextExamDate) return []
  return (
    plan.value.groups.find((g) => g.examDate === plan.value?.nextExamDate)?.subjects ?? []
  )
})

const nextExamNames = computed(() => {
  const names = nextExamSubjects.value.map((s) => s.name)
  if (names.length <= 3) return names.join('、')
  return names.slice(0, 3).join('、') + ` 等 ${names.length} 门`
})

function formatExamDate(date?: string) {
  if (!date) return ''
  const d = new Date(date + 'T00:00:00')
  return `${d.getMonth() + 1} 月 ${d.getDate()} 日`
}

function examBadge(s: SubjectView) {
  if (!s.examDate) return '未排考期'
  const d = new Date(s.examDate + 'T00:00:00')
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const days = Math.round((d.getTime() - today.getTime()) / 86400000)
  const label = `${d.getMonth() + 1}/${d.getDate()}`
  if (days < 0) return `${label} 已结束`
  if (days === 0) return `${label} 今天考试`
  return `${label} · ${days} 天`
}

const dateText = (() => {
  const d = new Date()
  const week = '日一二三四五六'[d.getDay()]
  return `${d.getMonth() + 1} 月 ${d.getDate()} 日 · 周${week}`
})()

const greeting = (() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了，翻两页就休息'
  if (h < 11) return '早上好，今天也要加油'
  if (h < 14) return '中午好，来做几道题'
  if (h < 18) return '下午好，保持这个节奏'
  return '晚上好，今天再进步一点'
})()

const todayPct = computed(() => {
  const n = overview.value?.todayAnswered ?? 0
  return Math.min(100, Math.round((n / goal) * 100))
})

const nudge = computed(() => {
  const n = overview.value?.todayAnswered ?? 0
  if (n === 0) return '今天还没开始，从下面选一门学科开练吧'
  if (n < goal) return `已经练了 ${n} 题，再练 ${goal - n} 题就达标`
  return '今日目标达成，给自己的坚持点个赞'
})

function initial(name: string) {
  return name.trim().slice(0, 1)
}

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [subs, ov, p] = await Promise.all([api.subjects(), api.overview(), api.examPlan()])
    subjects.value = subs
    overview.value = ov
    plan.value = p
  } catch (e) {
    loadError.value = (e as Error).message
    toast((e as Error).message)
  } finally {
    loading.value = false
  }
}

async function start(subject: SubjectView) {
  if (starting.value) return
  if (subject.choiceCount === 0) {
    toast('这门课还没有选择题，可以去「知识点」背主观题')
    return
  }
  starting.value = true
  try {
    const session = await api.createSession({
      subjectId: subject.id,
      mode: 'RANDOM',
      count: practiceSettings.value.count
    })
    router.push(`/practice/${session.id}`)
  } catch (e) {
    toast((e as Error).message)
  } finally {
    starting.value = false
  }
}

async function removeSubject(s: SubjectView) {
  if (deleting.value != null) return
  const ok = await confirmDialog({
    title: '删除学科？',
    message: `「${s.name}」会从当前专业里删除。`,
    detail:
      s.questionCount > 0
        ? `会同时删除 ${s.questionCount} 道题、作答记录和错题，删除后不可恢复。`
        : s.examDate
          ? '这门学科还没有题目，会从「还没题目的学科」里移除，并取消考试计划里的勾选。之后仍可在考试计划中重新勾选。'
          : '这门学科还没有题目，删除后专业下就少一门。',
    confirmText: s.questionCount > 0 ? '继续' : '删除学科',
    cancelText: '取消',
    tone: 'danger'
  })
  if (!ok) return

  // 有题目的学科多一步输入确认，避免误删整门课
  if (s.questionCount > 0) {
    const typed = await promptDialog({
      title: '再确认一次',
      message: `删除后无法恢复。请输入学科名称「${s.name}」来确认。`,
      label: '学科名称',
      placeholder: s.name,
      confirmText: '确认删除',
      tone: 'danger',
      required: true
    })
    if (typed == null) return
    if (typed.trim() !== s.name.trim()) {
      toast('输入的名称不一致，已取消删除')
      return
    }
  }

  deleting.value = s.id
  try {
    await api.deleteSubject(s.id)
    toast(s.examDate ? `已删除「${s.name}」，并取消考试计划勾选` : `已删除「${s.name}」`)
    await load()
  } catch (e) {
    toast((e as Error).message)
  } finally {
    deleting.value = null
  }
}

async function reviewSubject(subject: SubjectView) {
  if (starting.value) return
  starting.value = true
  try {
    const session = await api.startReview(practiceSettings.value.count, subject.id)
    router.push(`/practice/${session.id}`)
  } catch (e) {
    toast((e as Error).message)
  } finally {
    starting.value = false
  }
}

async function startDueReview() {
  if (!overview.value?.dueCount) {
    toast('今天没有到期需要复习的选择题')
    return
  }
  if (starting.value) return
  starting.value = true
  try {
    const session = await api.startReview(practiceSettings.value.count)
    router.push(`/practice/${session.id}`)
  } catch (e) {
    toast((e as Error).message)
  } finally {
    starting.value = false
  }
}

function pct(s: SubjectView) {
  if (!s.choiceCount) return 0
  return Math.min(100, Math.round((s.answeredCount / s.choiceCount) * 100))
}

function onSaveSettings(v: PracticeSettings) {
  savePracticeSettings(v)
  showSettings.value = false
  toast(`已保存：每次 ${v.count} 题，今日目标 ${v.goal} 题`)
}
</script>

<template>
  <div class="page home">
    <header class="home-head">
      <div>
        <div class="eyebrow">{{ dateText }}</div>
        <h1 class="hello">{{ greeting }}</h1>
        <p class="sub">
          {{ overview?.subjectCount ?? 0 }} 门课 · 选择题 {{ overview?.choiceCount ?? 0 }} 道 ·
          知识点 {{ overview?.cardCount ?? 0 }} 个
        </p>
      </div>
      <div v-if="overview?.streakDays" class="streak">
        <span class="s-flame">🔥</span>
        <div>
          <b>{{ overview.streakDays }}</b>
          <span>连续学习</span>
        </div>
      </div>
    </header>

    <div v-if="loadError" class="card empty">
      首页加载失败：{{ loadError }}
      <div style="margin-top: 12px">
        <button class="btn sm" @click="load">重试</button>
      </div>
    </div>

    <section class="card hero">
      <div class="hero-top">
        <div class="goal-ring" :style="{ '--p': todayPct }">
          <div class="goal-in">
            <b>{{ overview?.todayAnswered ?? 0 }}</b>
            <span>/ {{ goal }} 题</span>
          </div>
        </div>

        <div class="hero-copy">
          <div class="hero-eyebrow">今日目标</div>
          <h2>{{ nudge }}</h2>
          <p>
            总正确率 {{ overview?.accuracy ?? 0 }}% · 错题 {{ overview?.wrongCount ?? 0 }} 道 ·
            待复习 {{ overview?.dueCount ?? 0 }} 题
          </p>
        </div>

        <button class="hero-set" title="设置每次题量和今日目标" @click="showSettings = true">
          ⚙ 练习设置
        </button>
      </div>

      <div v-if="plan?.nextExamDate" class="exam-strip">
        <div class="exam-days">
          <b>{{ Math.max(plan.daysLeft ?? 0, 0) }}</b>
          <span>{{ (plan.daysLeft ?? 0) >= 0 ? '天后考试' : '考试已结束' }}</span>
        </div>
        <div class="exam-copy">
          <b>{{ formatExamDate(plan.nextExamDate) }} · 本次考期</b>
          <span>{{ nextExamNames || '还没有安排科目' }}</span>
        </div>
        <button class="exam-edit" @click="router.push('/plan')">调整计划</button>
      </div>

      <div class="quick">
        <button
          class="quick-item"
          :class="{ link: overview?.dueCount }"
          :disabled="starting"
          @click="startDueReview"
        >
          <div class="q-n">{{ overview?.dueCount ?? 0 }}</div>
          <div class="q-l">选择题待复习</div>
        </button>
        <button class="quick-item link" @click="router.push('/cards')">
          <div class="q-n warn">{{ overview?.dueCardCount ?? 0 }}</div>
          <div class="q-l">知识点待复习</div>
        </button>
        <button class="quick-item link" @click="router.push('/stats')">
          <div class="q-n ok">{{ overview?.accuracy ?? 0 }}<span class="unit">%</span></div>
          <div class="q-l">总正确率</div>
        </button>
        <button class="quick-item link" @click="router.push('/wrong')">
          <div class="q-n bad">{{ overview?.wrongCount ?? 0 }}</div>
          <div class="q-l">错题</div>
        </button>
      </div>
    </section>

    <div v-if="!loading && !plan?.nextExamDate && subjects.length" class="card plan-tip">
      <div>
        <b>还没设置考试日期</b>
        <span>设好本次考期后，首页会显示倒计时，复习安排也会围绕这次考试。</span>
      </div>
      <button class="btn sm" @click="router.push('/plan')">去设置考试计划</button>
    </div>

    <div id="my-subjects" class="sec-title subjects-title">
      <div class="st-left">
        <span>我的学科</span>
        <span v-if="subjects.length" class="muted count">
          {{ activeSubjects.length }} 门有题<template v-if="idleSubjects.length"> · {{ idleSubjects.length }} 门待导入</template>
        </span>
      </div>
    </div>

    <div v-if="loading" class="card empty">正在加载…</div>

    <div v-else class="grid3">
      <div v-for="s in activeSubjects" :key="s.id" class="card subject" :style="{ '--sc': s.color }">
        <div class="subject-head">
          <span class="subject-badge">{{ initial(s.name) }}</span>
          <h4 :title="s.name">{{ s.name }}</h4>
          <span class="subject-pct">{{ pct(s) }}%</span>
        </div>
        <div class="meta">
          <span>选择题 {{ s.choiceCount }}</span>
          <span>知识点 {{ s.cardCount }}</span>
          <span v-if="s.pendingCount > 0" class="meta-warn">待补 {{ s.pendingCount }}</span>
          <span class="meta-exam" :class="{ off: !s.examDate }">{{ examBadge(s) }}</span>
        </div>
        <div class="bar"><i :style="{ width: pct(s) + '%', background: s.color }"></i></div>
        <div class="foot">
          <span>已练 {{ s.answeredCount }} / {{ s.choiceCount }} · {{ s.accuracy }}% 正确</span>
        </div>
        <div class="subject-actions">
          <button
            v-if="s.choiceCount > 0"
            class="mini primary"
            :disabled="starting"
            @click="start(s)"
          >
            练选择题
          </button>
          <button
            v-else-if="s.cardCount > 0"
            class="mini primary"
            @click="router.push(`/learn/${s.id}`)"
          >
            背知识点
          </button>
          <button
            v-if="s.dueCount > 0"
            class="mini review"
            :disabled="starting"
            title="只复习这门学科到期的选择题"
            @click="reviewSubject(s)"
          >
            复习（{{ s.dueCount }}）
          </button>
          <button
            v-if="s.choiceCount > 0 && s.cardCount > 0"
            class="mini"
            @click="router.push(`/learn/${s.id}`)"
          >
            知识点（{{ s.cardCount }}）
          </button>
          <button
            v-if="s.pendingCount > 0"
            class="mini danger"
            @click="router.push(`/fill/${s.id}`)"
          >
            补答案（{{ s.pendingCount }}）
          </button>
        </div>
      </div>
    </div>

    <div v-if="!loading && !activeSubjects.length && !idleSubjects.length" class="card empty">
      还没有题目。先去 <span class="lnk" @click="router.push('/import')">导入题库</span>，从你的资料库里选一个文件夹。
    </div>

    <div v-if="!loading && idleSubjects.length" class="idle">
      <div class="sec-title">
        <span>还没题目的学科</span>
        <span class="muted" style="font-size: 13px; font-weight: 400">{{ idleSubjects.length }} 门</span>
      </div>
      <div class="idle-list">
        <div v-for="s in idleSubjects" :key="s.id" class="idle-card">
          <span class="dot" :style="{ background: s.color }"></span>
          <span class="idle-name" :title="s.name">{{ s.name }}</span>
          <button class="mini" @click="router.push({ path: '/import', query: { subject: s.id } })">去导入</button>
          <button
            class="mini sub-del"
            :disabled="deleting === s.id"
            title="删除后考试计划仍会保留这门学科"
            @click="removeSubject(s)"
          >
            {{ deleting === s.id ? '删除中…' : '删除' }}
          </button>
        </div>
      </div>
    </div>

    <PracticeSettingsDialog
      v-if="showSettings"
      :count="practiceCount"
      :goal="goal"
      @close="showSettings = false"
      @save="onSaveSettings"
    />
  </div>
</template>

<style scoped>
.home-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 20px;
}
.eyebrow {
  margin-bottom: 7px;
  font-size: 11.5px;
  font-weight: 700;
  letter-spacing: .16em;
  text-transform: uppercase;
  color: var(--muted);
}
.home-head .hello {
  margin: 0 0 6px;
}
.home-head .sub {
  margin: 0;
}
.streak {
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px 8px 12px;
  border-radius: 14px;
  border: 1px solid #fed7aa;
  background: linear-gradient(135deg, #fffaf0, #ffedd5);
  color: #b45309;
  box-shadow: 0 14px 28px -22px rgba(180, 83, 9, .6);
}
.streak .s-flame {
  font-size: 20px;
  line-height: 1;
}
.streak b {
  display: block;
  font-size: 18px;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}
.streak span {
  display: block;
  font-size: 10.5px;
  opacity: .75;
}

.hero {
  position: relative;
  overflow: hidden;
  padding: 24px 26px 22px;
  border: none;
  color: #fff;
  background:
    radial-gradient(440px 240px at 86% -24%, rgba(255, 255, 255, .20), transparent 70%),
    radial-gradient(380px 260px at -4% 118%, rgba(18, 181, 165, .30), transparent 72%),
    linear-gradient(120deg, #34306e 0%, #4b45c9 52%, #6a5cf6 100%);
  box-shadow: 0 30px 64px -34px rgba(64, 58, 180, .75);
}
.hero-top {
  display: flex;
  align-items: center;
  gap: 22px;
  flex-wrap: wrap;
}
.goal-ring {
  --p: 0;
  position: relative;
  flex: none;
  width: 96px;
  height: 96px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: conic-gradient(#fff calc(var(--p) * 1%), rgba(255, 255, 255, .22) 0);
  box-shadow: 0 12px 30px -14px rgba(0, 0, 0, .5);
}
.goal-ring::before {
  content: '';
  position: absolute;
  inset: 9px;
  border-radius: 50%;
  background: rgba(35, 31, 88, .55);
  backdrop-filter: blur(4px);
}
.goal-in {
  position: relative;
  text-align: center;
  line-height: 1.1;
}
.goal-in b {
  font-size: 26px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
}
.goal-in span {
  display: block;
  margin-top: 3px;
  font-size: 11px;
  color: rgba(255, 255, 255, .7);
}
.hero-copy {
  flex: 1;
  min-width: 220px;
}
.hero-eyebrow {
  margin-bottom: 6px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .18em;
  color: rgba(255, 255, 255, .6);
}
.hero-copy h2 {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 700;
  line-height: 1.45;
  letter-spacing: -.01em;
}
.hero-copy p {
  margin: 0;
  font-size: 12.5px;
  color: rgba(255, 255, 255, .72);
}
.hero-set {
  align-self: flex-start;
  margin-left: auto;
  flex: none;
  height: 32px;
  padding: 0 12px;
  border: 1px solid rgba(255, 255, 255, .28);
  border-radius: 10px;
  background: rgba(255, 255, 255, .12);
  color: rgba(255, 255, 255, .9);
  font-family: inherit;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition: background .15s, border-color .15s, transform .15s;
}
.hero-set:hover {
  background: rgba(255, 255, 255, .2);
  border-color: rgba(255, 255, 255, .45);
  transform: translateY(-1px);
}
.exam-strip {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 18px;
  padding: 12px 14px;
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, .2);
  background: rgba(255, 255, 255, .12);
  backdrop-filter: blur(4px);
}
.exam-days {
  flex: none;
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 86px;
  padding-right: 16px;
  border-right: 1px solid rgba(255, 255, 255, .18);
}
.exam-days b {
  font-size: 27px;
  font-weight: 800;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}
.exam-days span {
  font-size: 11px;
  color: rgba(255, 255, 255, .68);
}
.exam-copy {
  flex: 1;
  min-width: 150px;
}
.exam-copy b {
  display: block;
  font-size: 13.5px;
}
.exam-copy span {
  display: block;
  margin-top: 1px;
  font-size: 11.5px;
  color: rgba(255, 255, 255, .7);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.exam-edit {
  flex: none;
  height: 30px;
  padding: 0 11px;
  border-radius: 9px;
  border: 1px solid rgba(255, 255, 255, .28);
  background: rgba(255, 255, 255, .14);
  color: rgba(255, 255, 255, .92);
  font-size: 11.5px;
  font-weight: 600;
}
.exam-edit:hover {
  background: rgba(255, 255, 255, .24);
}
.quick {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin-top: 20px;
}
.quick-item {
  padding: 12px 14px;
  border-radius: 13px;
  border: 1px solid rgba(255, 255, 255, .14);
  background: rgba(255, 255, 255, .09);
  color: #fff;
  text-align: left;
  transition: .16s;
}
.quick-item.link {
  cursor: pointer;
}
.quick-item.link:hover {
  background: rgba(255, 255, 255, .17);
  transform: translateY(-1px);
}
.quick-item .q-n {
  font-size: 22px;
  font-weight: 800;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
}
.quick-item .q-n .unit {
  font-size: 12px;
  font-weight: 700;
  margin-left: 1px;
}
.quick-item .q-n.warn {
  color: #ffd18a;
}
.quick-item .q-n.ok {
  color: #9df0dc;
}
.quick-item .q-n.bad {
  color: #ffb4b4;
}
.quick-item .q-l {
  margin-top: 2px;
  font-size: 11.5px;
  color: rgba(255, 255, 255, .68);
}

.subject {
  padding: 18px 18px 16px;
}
.subjects-title {
  align-items: flex-end;
  scroll-margin-top: 18px;
}
.subjects-title .st-left {
  display: flex;
  align-items: baseline;
  gap: 9px;
  flex-wrap: wrap;
}
.subjects-title .count {
  font-size: 13px;
  font-weight: 400;
}
.subject .meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  column-gap: 12px;
  row-gap: 4px;
}
.subject .meta > span {
  white-space: nowrap;
}
.subject .meta .meta-warn {
  padding: 0 7px;
  border-radius: 999px;
  color: var(--warn);
  background: var(--warn-soft);
  font-weight: 700;
}
.subject .meta .meta-exam {
  padding: 0 7px;
  border-radius: 999px;
  color: var(--primary);
  background: var(--primary-soft);
  font-weight: 700;
}
.subject .meta .meta-exam.off {
  color: var(--muted);
  background: #f0f2f8;
  font-weight: 500;
}
.plan-tip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 18px;
  padding: 15px 18px;
  border: 1px dashed #cfd3f5;
  background: linear-gradient(100deg, #f4f5ff, #fff);
}
.plan-tip b {
  display: block;
  font-size: 13.5px;
  margin-bottom: 2px;
}
.plan-tip span {
  font-size: 12.5px;
  color: var(--muted);
}
.btn.sm {
  height: 34px;
  padding: 0 14px;
  font-size: 12.5px;
  flex: none;
}
.subject-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
  margin-top: 13px;
  max-width: 248px;
}
.subject-actions .mini {
  width: 100%;
  padding-left: 8px;
  padding-right: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.subject-actions .mini.primary {
  border-color: transparent;
  color: #fff;
  background: linear-gradient(135deg, color-mix(in srgb, var(--sc, var(--primary)) 88%, #fff), var(--sc, var(--primary)));
  box-shadow: 0 10px 20px -12px color-mix(in srgb, var(--sc, var(--primary)) 85%, transparent);
}
.subject-actions .mini.primary:hover {
  color: #fff;
  border-color: transparent;
  background: linear-gradient(135deg, color-mix(in srgb, var(--sc, var(--primary)) 78%, #fff), color-mix(in srgb, var(--sc, var(--primary)) 88%, #000));
}
.subject-actions .mini.review {
  color: #b45309;
  border-color: #f8d9a8;
  background: var(--warn-soft);
}
.subject-actions .mini.review:hover {
  border-color: var(--warn);
  background: #ffefd3;
}
.subject-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}
.subject-badge {
  flex: none;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-size: 14px;
  font-weight: 800;
  color: var(--sc, var(--primary));
  background: color-mix(in srgb, var(--sc, var(--primary)) 13%, #fff);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--sc, var(--primary)) 22%, transparent);
}
.subject h4 {
  flex: 1;
  min-width: 0;
  margin: 0;
  font-size: 14.5px;
  line-height: 1.4;
  display: block;
}
.subject-pct {
  flex: none;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 700;
  color: var(--sc, var(--primary));
  background: color-mix(in srgb, var(--sc, var(--primary)) 10%, #fff);
}
.subject .meta {
  margin-left: 42px;
  margin-bottom: 14px;
}
.subject .bar {
  margin-top: auto;
}
.subject .foot {
  flex-wrap: wrap;
  row-gap: 6px;
  align-items: center;
}
.subject .foot .foot-del {
  margin-left: auto;
  height: 27px;
  padding: 0 10px;
  border: 1px solid #f5c2c2;
  border-radius: 8px;
  background: var(--bad-soft);
  color: var(--bad);
  font-family: inherit;
  font-size: 11.5px;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
  transition: border-color .15s, background .15s, transform .15s;
}
.subject .foot .foot-del:hover {
  border-color: var(--bad);
  background: #fbdede;
  transform: translateY(-1px);
}
.subject .foot .foot-del:disabled {
  opacity: .45;
  cursor: not-allowed;
  transform: none;
}
.idle {
  margin-top: 4px;
}
.idle-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 8px;
}
.idle-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 10px 9px 13px;
  border-radius: 13px;
  border: 1px dashed var(--line-strong);
  background: rgba(255, 255, 255, .62);
}
.idle-card .dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.idle-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--fg);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.idle-card .mini {
  flex: none;
  height: 29px;
  padding: 0 10px;
  font-size: 12px;
}
.idle-card .sub-del {
  color: var(--bad);
  border-color: #f5c2c2;
  background: var(--bad-soft);
  font-weight: 600;
}
.idle-card .sub-del:hover {
  border-color: var(--bad);
  background: #fbdede;
}
@media (max-width: 860px) {
  .home-head {
    align-items: flex-start;
  }
  .streak {
    padding: 7px 10px;
  }
  .hero {
    padding: 20px 18px;
  }
  .hero-top {
    gap: 16px;
  }
  .goal-ring {
    width: 82px;
    height: 82px;
  }
  .goal-in b {
    font-size: 22px;
  }
  .quick {
    grid-template-columns: repeat(2, 1fr);
  }
  .exam-strip {
    gap: 12px;
  }
  .exam-edit {
    padding: 0 9px;
  }
  .subject .meta {
    margin-left: 0;
  }
}
</style>
