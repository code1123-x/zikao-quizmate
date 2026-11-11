<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, isSubjective, TYPE_NAME, type AnswerResult, type Option, type SessionView } from '../api'
import { toast } from '../toast'
import { confirmDialog } from '../dialog'

const route = useRoute()
const router = useRouter()

const session = ref<SessionView | null>(null)
const index = ref(0)
const chosen = ref<string[]>([])
const judged = ref(false)
const result = ref<AnswerResult | null>(null)
const textAnswer = ref('')
const showRef = ref(false)
const verdicts = ref<Record<number, boolean>>({})
const answeredValues = ref<Record<number, string>>({})
const seconds = ref(0)
const sheetOpen = ref(false)
const busy = ref(false)
const examSaveVersions = ref<Record<number, number>>({})
const examSaveState = ref<Record<number, 'saving' | 'saved' | 'failed'>>({})
// 每道题单独的计时起点，提交时只上报本题耗时，避免把整场时间重复累加
const questionStartedAt = ref(Date.now())

let timer: number | undefined

function draftKey(id: number) {
  return `quizmate.practiceDraft.${id}`
}

function saveDraft() {
  if (!session.value) return
  localStorage.setItem(draftKey(session.value.id), JSON.stringify({
    index: index.value,
    flagged: flagged.value,
    chosen: chosen.value,
    textAnswer: textAnswer.value
  }))
}

function clearDraft() {
  if (session.value) localStorage.removeItem(draftKey(session.value.id))
}

const current = computed(() => session.value?.questions[index.value])
const total = computed(() => session.value?.questions.length ?? 0)
const answeredCount = computed(() => Object.keys(answeredValues.value).length)
const progress = computed(() => (total.value ? (answeredCount.value / total.value) * 100 : 0))
const isLast = computed(() => index.value === total.value - 1)
const typeName = computed(() => (current.value ? TYPE_NAME[current.value.type] ?? current.value.type : ''))

const isExam = computed(() => session.value?.mode === 'EXAM')
const remaining = ref(0)
const flagged = ref<number[]>([])
const autoSubmitted = ref(false)

const isFlagged = computed(() => (current.value ? flagged.value.includes(current.value.id) : false))

const options = computed<Option[]>(() => {
  const q = current.value
  if (!q) return []
  if (q.type === 'JUDGE' && (!q.options || q.options.length === 0)) {
    return [
      { key: '对', text: '正确' },
      { key: '错', text: '错误' }
    ]
  }
  return q.options ?? []
})

onMounted(async () => {
  try {
    const s = await api.getSession(Number(route.params.id))
    session.value = s
    let draft: { index?: number; flagged?: number[]; chosen?: string[]; textAnswer?: string } = {}
    try { draft = JSON.parse(localStorage.getItem(draftKey(s.id)) || '{}') } catch { /* ignore invalid local draft */ }
    // 恢复进度：跳到第一道没答过的题
    s.questions.forEach((q) => {
      const v = s.answered?.[q.id]
      if (v !== undefined && v !== null) answeredValues.value[q.id] = String(v)
    })
    const firstUnanswered = s.questions.findIndex((q) => answeredValues.value[q.id] === undefined)
    index.value = Number.isInteger(draft.index) && draft.index! >= 0 && draft.index! < s.questions.length
      ? draft.index!
      : firstUnanswered < 0 ? 0 : firstUnanswered
    flagged.value = Array.isArray(draft.flagged) ? draft.flagged : []
    chosen.value = Array.isArray(draft.chosen) ? draft.chosen : []
    textAnswer.value = typeof draft.textAnswer === 'string' ? draft.textAnswer : ''
    questionStartedAt.value = Date.now()
    if (s.mode === 'EXAM' && s.durationMinutes && s.startedAt) {
      const end = Date.parse(s.startedAt) + s.durationMinutes * 60000
      tickExam(end)
      timer = window.setInterval(() => tickExam(end), 1000)
    } else {
      timer = window.setInterval(() => seconds.value++, 1000)
    }
  } catch (e) {
    toast((e as Error).message)
    router.replace('/')
  }
})

function tickExam(end: number) {
  remaining.value = Math.max(0, Math.round((end - Date.now()) / 1000))
  if (remaining.value <= 0 && !autoSubmitted.value) {
    autoSubmitted.value = true
    window.clearInterval(timer)
    toast('考试时间到，已自动交卷')
    finishWhenIdle()
  }
}

/** 自动交卷时如果正好有请求在途，等它结束再交卷，避免被 busy 挡掉。 */
function finishWhenIdle() {
  if (busy.value) {
    window.setTimeout(finishWhenIdle, 200)
    return
  }
  finish(true)
}

onBeforeUnmount(() => window.clearInterval(timer))

watch(index, () => {
  questionStartedAt.value = Date.now()
  chosen.value = []
  judged.value = false
  result.value = null
  textAnswer.value = ''
  showRef.value = false
  sheetOpen.value = false
  if (isExam.value && current.value) {
    const v = answeredValues.value[current.value.id]
    if (v) chosen.value = v.split('')
  }
})

watch([chosen, textAnswer, flagged], saveDraft, { deep: true })

function toggleFlag() {
  const q = current.value
  if (!q) return
  const i = flagged.value.indexOf(q.id)
  if (i >= 0) flagged.value.splice(i, 1)
  else flagged.value.push(q.id)
}

function toggleOption(key: string) {
  if (busy.value) return
  const q = current.value!
  if (isExam.value) {
    if (q.type === 'MULTIPLE') {
      const i = chosen.value.indexOf(key)
      if (i >= 0) chosen.value.splice(i, 1)
      else chosen.value.push(key)
      // 清空最后一个选项时也要提交，否则服务端会保留上一次的多选答案
      submitExam([...chosen.value].sort().join(''))
      return
    }
    chosen.value = [key]
    submitExam(key)
    return
  }
  if (judged.value) return
  if (q.type === 'MULTIPLE') {
    const i = chosen.value.indexOf(key)
    if (i >= 0) chosen.value.splice(i, 1)
    else chosen.value.push(key)
    return
  }
  chosen.value = [key]
  submit(key)
}

/** 考试模式：只提交答案，不显示对错 */
async function submitExam(value: string) {
  const q = current.value
  if (!q || !session.value) return
  const version = (examSaveVersions.value[q.id] ?? 0) + 1
  examSaveVersions.value[q.id] = version
  examSaveState.value[q.id] = 'saving'
  busy.value = true
  try {
    const costMs = Math.max(0, Date.now() - questionStartedAt.value)
    await api.answer(session.value.id, { questionId: q.id, answer: value, costMs })
    if (examSaveVersions.value[q.id] !== version) return
    if (value) answeredValues.value[q.id] = value
    else delete answeredValues.value[q.id]
    examSaveState.value[q.id] = 'saved'
    questionStartedAt.value = Date.now()
  } catch (e) {
    if (examSaveVersions.value[q.id] === version) examSaveState.value[q.id] = 'failed'
    toast((e as Error).message)
  } finally {
    if (examSaveVersions.value[q.id] === version) busy.value = false
  }
}

async function submit(value: string, selfRating?: string) {
  const q = current.value
  if (!q || busy.value) return
  busy.value = true
  try {
    const res = await api.answer(session.value!.id, {
      questionId: q.id,
      answer: value,
      selfRating,
      costMs: Math.max(0, Date.now() - questionStartedAt.value)
    })
    questionStartedAt.value = Date.now()
    result.value = res
    judged.value = true
    answeredValues.value[q.id] = value
    verdicts.value[q.id] = res.correct
    if (q.type === 'MULTIPLE' && !selfRating) {
      chosen.value = value.split('')
    }
  } catch (e) {
    toast((e as Error).message)
  } finally {
    busy.value = false
  }
}

function submitMulti() {
  if (!chosen.value.length) {
    toast('至少选一项')
    return
  }
  submit([...chosen.value].sort().join(''))
}

function submitFill() {
  if (!textAnswer.value.trim()) {
    toast('先写答案')
    return
  }
  submit(textAnswer.value.trim())
}

function submitSubjective(rating: string) {
  submit(textAnswer.value.trim() || '（未作答）', rating)
}

async function next() {
  if (busy.value) return
  if (!isExam.value && !judged.value && current.value && current.value.type !== 'FILL' && !isSubjective(current.value.type)) {
    toast('先作答再进入下一题')
    return
  }
  if (isLast.value) {
    await finish()
    return
  }
  index.value++
}

function prev() {
  if (index.value > 0) index.value--
}

async function finish(force = false) {
  if (busy.value) return
  if (!force && isExam.value && answeredCount.value < total.value) {
    const left = total.value - answeredCount.value
    const ok = await confirmDialog({
      title: '还有题目没做完',
      message: `还有 ${left} 题没作答，现在交卷吗？`,
      detail: '交卷后就不能再修改答案了。',
      confirmText: '确认交卷',
      cancelText: '继续作答',
      tone: 'warn'
    })
    if (!ok) return
  }
  busy.value = true
  try {
    await api.finish(session.value!.id)
    clearDraft()
    router.replace(`/result/${session.value!.id}`)
  } catch (e) {
    toast((e as Error).message)
  } finally {
    busy.value = false
  }
}

async function quit() {
  if (answeredCount.value > 0) {
    const ok = await confirmDialog({
      title: '退出这次练习？',
      message: '当前还有未完成的练习。',
      detail: '进度会保存，下次回来可以继续。',
      confirmText: '退出练习',
      cancelText: '继续做题',
      tone: 'warn'
    })
    if (!ok) return
  }
  router.push('/')
}

function jump(i: number) {
  index.value = i
  sheetOpen.value = false
}

function optionClass(key: string) {
  if (isExam.value) {
    return { sel: chosen.value.includes(key) }
  }
  if (!judged.value || !result.value) {
    return { sel: chosen.value.includes(key) }
  }
  const answer = result.value.correctAnswer ?? ''
  const isCorrect = current.value?.type === 'MULTIPLE' ? answer.includes(key) : answer === key
  if (isCorrect) return { correct: true }
  if (chosen.value.includes(key)) return { wrong: true }
  return {}
}

function cellClass(i: number) {
  const q = session.value!.questions[i]
  const v = verdicts.value[q.id]
  return {
    cur: i === index.value,
    answered: answeredValues.value[q.id] !== undefined && v === undefined,
    ok: v === true,
    bad: v === false,
    flagged: flagged.value.includes(q.id)
  }
}

function fmt(sec: number) {
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

function onKey(e: KeyboardEvent) {
  const target = e.target as HTMLElement | null
  // 输入框/文本域里不要抢键：主观题需要回车换行，填空题由输入框自己处理回车
  if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA'
    || target.tagName === 'SELECT' || target.isContentEditable)) {
    return
  }
  // 按钮自身已经有原生 Enter/空格点击，别再触发一次“下一题”
  if (target?.tagName === 'BUTTON' && (e.key === 'Enter' || e.key === ' ')) return
  if (e.ctrlKey || e.metaKey || e.altKey) return
  const q = current.value
  if (!q) return
  if (e.key >= '1' && e.key <= '9') {
    const opt = options.value[Number(e.key) - 1]
    if (opt) toggleOption(opt.key)
  } else if (e.key === 'Enter') {
    if (q.type === 'MULTIPLE' && !judged.value) submitMulti()
    else if (q.type === 'FILL' && !judged.value) submitFill()
    else next()
  } else if (e.key === 'ArrowRight') {
    next()
  } else if (e.key === 'ArrowLeft') {
    prev()
  }
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div v-if="session && current" class="practice">
    <div class="p-head">
      <button class="back" @click="quit">← 退出</button>
      <div class="p-title">
        {{ session.subjectName }} ·
        {{
          session.mode === 'WRONG'
            ? '错题重练'
            : session.mode === 'CUSTOM'
              ? '自定义练习'
              : session.mode === 'REVIEW'
                ? '今日复习'
                : session.mode === 'EXAM'
                  ? '模拟考试'
                  : '随机练习'
        }}
      </div>
      <div class="timer" :class="{ danger: isExam && remaining <= 300 }">
        {{ isExam ? fmt(remaining) : fmt(seconds) }}
      </div>
      <button v-if="isExam" class="nav-btn" style="padding: 7px 12px" @click="toggleFlag">
        {{ isFlagged ? '★ 已标记' : '☆ 标记' }}
      </button>
      <button class="nav-btn sheet-toggle" style="padding: 7px 12px" @click="sheetOpen = true">答题卡</button>
    </div>
    <div class="prog"><i :style="{ width: progress + '%' }"></i></div>

    <div class="p-body">
      <div class="p-main">
        <div class="qmeta">
          <span class="qnum">第 {{ index + 1 }} / {{ total }} 题</span>
          <span class="pill">{{ typeName }}</span>
          <span v-if="current.source">{{ current.source }}</span>
          <span v-if="current.year">· {{ current.year }} 年</span>
          <span v-if="isExam" class="save-state" :class="examSaveState[current.id]">
            {{ examSaveState[current.id] === 'saving' ? '保存中…' : examSaveState[current.id] === 'failed' ? '保存失败，请重试' : answeredValues[current.id] !== undefined ? '已保存' : '未作答' }}
          </span>
        </div>

        <div class="qbody">{{ current.stem }}</div>

        <!-- 客观题 -->
        <div v-if="current.type !== 'FILL' && !isSubjective(current.type)" :class="current.type === 'JUDGE' ? 'judge-row' : 'opts'">
          <button
            v-for="opt in options"
            :key="opt.key"
            class="opt"
            :class="optionClass(opt.key)"
            :disabled="judged"
            @click="toggleOption(opt.key)"
          >
            <span class="k">{{ opt.key === '对' ? '✓' : opt.key === '错' ? '✕' : opt.key }}</span>
            <span class="t">{{ opt.text }}</span>
            <span v-if="judged && result" class="flag" :class="result.correctAnswer.includes(opt.key) ? 'ok' : (chosen.includes(opt.key) ? 'bad' : '')">
              {{ result.correctAnswer.includes(opt.key) ? '✔ 正确答案' : (chosen.includes(opt.key) ? '✘ 你的选择' : '') }}
            </span>
          </button>
        </div>

        <div v-if="current.type === 'MULTIPLE' && !judged && !isExam" style="margin-top: 16px; display: flex; align-items: center; gap: 12px">
          <span class="muted" style="font-size: 13px">已选 {{ chosen.length }} 项</span>
          <button class="btn" :disabled="!chosen.length" @click="submitMulti">确认答案</button>
        </div>

        <div v-if="isExam && current.type === 'MULTIPLE'" class="muted" style="margin-top: 14px; font-size: 13px">
          已选 {{ chosen.length }} 项（自动保存，交卷后统一判分）
        </div>

        <!-- 填空 -->
        <div v-if="current.type === 'FILL'" class="answer-box">
          <input
            v-model="textAnswer"
            :disabled="judged"
            placeholder="填写答案…"
            style="width: 100%; padding: 13px 14px; border: 1.5px solid var(--line); border-radius: 12px; font: inherit; outline: none; background: var(--card); color: var(--fg)"
            @keyup.enter="!judged && submitFill()"
          />
          <div v-if="!judged" style="margin-top: 12px">
            <button class="btn" @click="submitFill">提交答案</button>
          </div>
        </div>

        <!-- 主观题 -->
        <div v-if="isSubjective(current.type)" class="answer-box">
          <textarea v-model="textAnswer" :disabled="judged" placeholder="写下你的答题要点，或只写关键词…"></textarea>
          <div v-if="!judged && !showRef" style="margin-top: 12px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap">
            <button class="btn soft" @click="showRef = true">对照参考答案</button>
            <span class="muted" style="font-size: 12.5px">主观题不自动判分，看完答案后自评</span>
          </div>
        </div>

        <!-- 判分结果 -->
        <div v-if="judged && result" class="analysis">
          <div class="ana-head">
            <span v-if="current.type !== 'TERM' && !isSubjective(current.type)" class="badge" :class="result.correct ? 'ok' : 'bad'">
              {{ result.correct ? '✔ 回答正确' : '✘ 回答错误' }}
            </span>
            <span v-if="!isSubjective(current.type)">
              正确答案 <b style="color: var(--ok)">{{ result.correctAnswer }}</b>
              <template v-if="answeredValues[current.id]"> · 你的答案 <b>{{ answeredValues[current.id] }}</b></template>
            </span>
            <span v-else>参考答案</span>
          </div>
          <div class="ana-body">
            <template v-if="isSubjective(current.type)">
              <div class="ref-answer"><b>参考要点</b>{{ result.correctAnswer }}</div>
              <div v-if="result.analysis" style="margin-top: 14px">{{ result.analysis }}</div>
            </template>
            <template v-else>{{ result.analysis || '这道题暂时没有解析。' }}</template>
          </div>
        </div>

        <!-- 主观题自评 -->
        <div v-if="isSubjective(current.type) && showRef && !judged" class="selfrate">
          <button @click="submitSubjective('MASTER')">🟢 答对了</button>
          <button @click="submitSubjective('VAGUE')">🟡 有印象但不全</button>
          <button @click="submitSubjective('NONE')">🔴 完全不会</button>
        </div>
      </div>

      <!-- 答题卡 -->
      <div v-if="sheetOpen" class="mask" @click="sheetOpen = false"></div>
      <aside class="p-side" :class="{ open: sheetOpen }">
        <div class="sheet-top">
          <span>答题卡</span>
          <button class="nav-btn sheet-close" style="padding: 4px 10px" @click="sheetOpen = false">✕</button>
        </div>
        <div class="sheet-grid">
          <button v-for="(q, i) in session.questions" :key="q.id" class="cell" :class="cellClass(i)" @click="jump(i)">
            {{ i + 1 }}
          </button>
        </div>
        <div class="legend">
          <template v-if="isExam">
            <span><i style="background: var(--primary-soft); border: 1px solid var(--primary)"></i>已答</span>
            <span><i style="background: var(--line)"></i>未答</span>
            <span><i style="background: var(--warn)"></i>已标记</span>
          </template>
          <template v-else>
            <span><i style="background: var(--ok)"></i>答对</span>
            <span><i style="background: var(--bad)"></i>答错</span>
            <span><i style="background: var(--primary-soft); border: 1px solid var(--primary)"></i>已答</span>
            <span><i style="background: var(--line)"></i>未答</span>
          </template>
        </div>
      </aside>
    </div>

    <div class="p-foot">
      <button class="nav-btn" @click="prev" :disabled="index === 0">← 上一题</button>
      <div class="spacer"></div>
      <button v-if="isExam" class="nav-btn" :disabled="busy" @click="finish()">交卷</button>
      <button class="nav-btn primary" :disabled="busy" @click="next">
        {{ isLast ? (isExam ? '交卷' : '交卷并查看结果') : '下一题' }}
      </button>
    </div>
  </div>
</template>
