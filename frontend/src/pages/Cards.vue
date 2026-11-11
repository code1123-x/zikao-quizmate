<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, type CardSummary } from '../api'
import { toast } from '../toast'

const router = useRouter()
const list = ref<CardSummary[]>([])
const loading = ref(true)
const error = ref('')

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    list.value = (await api.cardSummary()).filter((s) => s.total > 0)
  } catch (e) {
    list.value = []
    error.value = (e as Error).message
    toast((e as Error).message)
  } finally {
    loading.value = false
  }
}

function pct(s: CardSummary) {
  if (!s.total) return 0
  return Math.round((s.mastered / s.total) * 100)
}
</script>

<template>
  <div class="page">
    <h1 class="hello">知识点</h1>
    <p class="sub">简答题、论述题、名词解释、填空题整理成的记忆卡片 · 先回想，再看答案</p>

    <div v-if="loading" class="card empty">正在加载…</div>
    <div v-else-if="error" class="card empty">
      加载失败：{{ error }}
      <div style="margin-top: 12px">
        <button class="btn sm" @click="load">重试</button>
      </div>
    </div>
    <div v-else-if="list.length === 0" class="card empty">还没有知识点卡片，先导入教材/讲义/真题。</div>

    <div v-else class="grid3">
      <div v-for="s in list" :key="s.subjectId" class="card subject" :style="{ '--sc': s.color }" @click="router.push(`/learn/${s.subjectId}`)">
        <h4><span class="dot" :style="{ background: s.color }"></span>{{ s.name }}</h4>
        <div class="meta">
          {{ s.total }} 个知识点
          <template v-if="s.due > 0"> · <span style="color: var(--warn)">待复习 {{ s.due }}</span></template>
        </div>
        <div class="bar"><i :style="{ width: pct(s) + '%', background: s.color }"></i></div>
        <div class="foot">
          <span>已掌握 {{ s.mastered }} / {{ s.total }}</span>
          <span>{{ pct(s) }}%</span>
        </div>
        <div class="subject-actions" @click.stop>
          <button class="mini" @click="router.push(`/learn/${s.subjectId}`)">开始背</button>
        </div>
      </div>
    </div>
  </div>
</template>
