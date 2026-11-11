<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, TYPE_NAME, type ResultView } from '../api'
import { toast } from '../toast'

const route = useRoute()
const router = useRouter()
const result = ref<ResultView | null>(null)
const loading = ref(true)
const error = ref('')

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    result.value = await api.result(Number(route.params.id))
  } catch (e) {
    result.value = null
    error.value = (e as Error).message
    toast((e as Error).message)
  } finally {
    loading.value = false
  }
}

// 未作答的题不算“错题”，单独统计；主观题交白卷（占位答案）仍算已作答
const wrongItems = computed(() => result.value?.items.filter((i) => i.answered && !i.correct) ?? [])
const isExam = computed(() => result.value?.mode === 'EXAM')
const score = computed(() => {
  const value = result.value
  if (!value) return 0
  if (value.totalScore <= 0) return value.accuracy
  return Math.round((value.earnedScore / value.totalScore) * 1000) / 10
})
const displayRate = computed(() => (isExam.value ? score.value : (result.value?.accuracy ?? 0)))
const ringStyle = computed(() => ({ '--p': displayRate.value }))
const passed = computed(() => score.value >= 60)
const modeText = computed(() => {
  switch (result.value?.mode) {
    case 'EXAM':
      return '模拟考试'
    case 'WRONG':
      return '错题重练'
    case 'CUSTOM':
      return '自定义练习'
    case 'REVIEW':
      return '今日复习'
    default:
      return '随机练习'
  }
})

function fmtCost(ms: number) {
  const sec = Math.floor(ms / 1000)
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m} 分 ${s} 秒`
}

async function again() {
  if (!result.value) return
  try {
    if (result.value.mode === 'EXAM') {
      const session = await api.createSession({
        subjectId: result.value.subjectId,
        mode: 'EXAM',
        count: result.value.total,
        durationMinutes: result.value.durationMinutes || 150
      })
      router.replace(`/practice/${session.id}`)
      return
    }
    // 综合复习（subjectId=0）没有对应学科，再来一组就继续复习到期的题
    const session =
      result.value.subjectId === 0
        ? await api.startReview(30)
        : await api.createSession({ subjectId: result.value.subjectId, mode: 'RANDOM', count: 20 })
    router.replace(`/practice/${session.id}`)
  } catch (e) {
    toast((e as Error).message)
  }
}
</script>

<template>
  <div class="page">
    <div v-if="loading" class="card empty">正在统计…</div>
    <div v-else-if="error" class="card empty">
      结果加载失败：{{ error }}
      <div style="margin-top: 12px">
        <button class="btn sm" @click="load">重试</button>
      </div>
    </div>

    <div v-else-if="result" class="result-wrap">
      <h1 class="hello">{{ isExam ? '考试结束' : '练习完成 🎉' }}</h1>
      <p class="sub">
        {{ result.subjectName }} · {{ modeText }} · 用时 {{ fmtCost(result.costMs) }}
        <span v-if="isExam" class="pass" :class="passed ? 'ok' : 'bad'">{{ passed ? '及格' : '不及格' }}</span>
      </p>

      <div class="card score-card">
        <div class="ring" :style="ringStyle">
          <div class="in">
            <b>{{ Math.round(displayRate) }}</b>
            <span>{{ isExam ? '得分' : '正确率' }}</span>
          </div>
        </div>
        <div class="result-stats">
          <div>
            <div class="n">{{ result.correct }} / {{ result.total }}</div>
            <div class="l">答对题数</div>
          </div>
          <div>
            <div class="n">{{ result.wrong }}</div>
            <div class="l">答错</div>
          </div>
          <div>
            <div class="n">{{ result.unanswered }}</div>
            <div class="l">未作答</div>
          </div>
          <div>
            <div class="n" style="color: var(--primary)">+{{ result.total - result.unanswered }}</div>
            <div class="l">进入复习队列</div>
          </div>
        </div>
      </div>

      <div class="sec-title"><span>本次错题</span></div>
      <div class="card" style="padding: 6px 20px">
        <div v-if="wrongItems.length === 0" class="empty">
          {{ result.unanswered > 0 ? '答过的题全部正确，未作答的题不计入错题。' : '全部答对，太强了 🎉' }}
        </div>
        <div v-for="item in wrongItems" :key="item.questionId" class="wrong-item">
          <p>{{ item.stem }}</p>
          <div class="meta">
            正确答案 <b>{{ item.correctAnswer }}</b>
            <template v-if="item.userAnswer"> · 你的答案 {{ item.userAnswer }}</template>
            · {{ TYPE_NAME[item.type] ?? item.type }}
          </div>
          <div v-if="item.analysis" class="wrong-analysis">{{ item.analysis }}</div>
        </div>
      </div>

      <div style="display: flex; gap: 10px; margin-top: 22px; flex-wrap: wrap">
        <button class="btn" @click="again">{{ isExam ? '再考一次' : '再练一组' }}</button>
        <button class="btn ghost" @click="router.push('/')">返回首页</button>
      </div>
    </div>
  </div>
</template>
