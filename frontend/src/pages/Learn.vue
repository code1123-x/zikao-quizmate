<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, TYPE_NAME, type CardView } from '../api'
import { toast } from '../toast'

const route = useRoute()
const router = useRouter()

const cards = ref<CardView[]>([])
const idx = ref(0)
const revealed = ref(false)
const done = ref(false)
const subjectName = ref('知识点')
const stats = ref({ good: 0, vague: 0, bad: 0 })
const busy = ref(false)

const current = computed(() => cards.value[idx.value])
const progress = computed(() => (cards.value.length ? (idx.value / cards.value.length) * 100 : 0))

onMounted(async () => {
  await loadCards()
  if (cards.value.length) window.addEventListener('keydown', onKey)
})

async function loadCards() {
  const subjectId = Number(route.params.subjectId)
  try {
    const [summary, list] = await Promise.all([api.cardSummary(), api.cards(subjectId, 50)])
    subjectName.value = summary.find((s) => s.subjectId === subjectId)?.name ?? '知识点'
    cards.value = list
    done.value = false
    idx.value = 0
    revealed.value = false
    stats.value = { good: 0, vague: 0, bad: 0 }
    if (!list.length) {
      toast('这门课还没有知识点')
      router.replace('/cards')
    }
  } catch (e) {
    toast((e as Error).message)
    router.replace('/cards')
  }
}

onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

function reveal() {
  if (!done.value) revealed.value = true
}

async function rate(rating: 'MASTER' | 'VAGUE' | 'NONE') {
  if (!current.value || busy.value) return
  busy.value = true
  try {
    await api.reviewCard(current.value.id, rating)
    if (rating === 'MASTER') stats.value.good++
    else if (rating === 'VAGUE') stats.value.vague++
    else stats.value.bad++
    if (idx.value >= cards.value.length - 1) {
      done.value = true
    } else {
      idx.value++
      revealed.value = false
    }
  } catch (e) {
    toast((e as Error).message)
  } finally {
    busy.value = false
  }
}

function restart() {
  loadCards()
}

function quit() {
  router.push('/cards')
}

function onKey(e: KeyboardEvent) {
  if (done.value) return
  if (!revealed.value && (e.key === ' ' || e.key === 'Enter')) {
    e.preventDefault()
    reveal()
    return
  }
  if (!revealed.value) return
  if (e.key === '1') rate('NONE')
  else if (e.key === '2') rate('VAGUE')
  else if (e.key === '3') rate('MASTER')
}
</script>

<template>
  <div class="practice">
    <div class="p-head">
      <button class="back" @click="quit">← 退出</button>
      <div class="p-title">{{ subjectName }} · 知识点</div>
      <div class="timer">{{ done ? cards.length : idx + 1 }} / {{ cards.length }}</div>
    </div>
    <div class="prog"><i :style="{ width: (done ? 100 : progress) + '%' }"></i></div>

    <div class="learn-body">
      <template v-if="!done && current">
        <div class="qmeta">
          <span class="pill">{{ TYPE_NAME[current.type] ?? current.type }}</span>
          <span v-if="current.source">{{ current.source }}</span>
          <span v-if="current.year">· {{ current.year }} 年</span>
        </div>

        <h2 class="learn-title">{{ current.title }}</h2>

        <button v-if="!revealed" class="btn" @click="reveal">显示答案（空格）</button>

        <template v-else>
          <div class="ref-answer"><b>参考答案</b>{{ current.answer }}</div>
          <div v-if="current.analysis" class="learn-note">{{ current.analysis }}</div>

          <div class="learn-hint">刚才想出来了吗？</div>
          <div class="selfrate">
            <button class="rate bad" :disabled="busy" @click="rate('NONE')">🔴 不会</button>
            <button class="rate mid" :disabled="busy" @click="rate('VAGUE')">🟡 模糊</button>
            <button class="rate good" :disabled="busy" @click="rate('MASTER')">🟢 会了</button>
          </div>
        </template>
      </template>

      <div v-else class="learn-done">
        <div class="big">🎉</div>
        <h3>这一组背完了</h3>
        <p>共 {{ cards.length }} 个知识点 · 会了 {{ stats.good }} · 模糊 {{ stats.vague }} · 不会 {{ stats.bad }}</p>
        <div style="display: flex; gap: 10px; justify-content: center; flex-wrap: wrap">
          <button class="btn" @click="restart">再来一组</button>
          <button class="btn ghost" @click="quit">返回知识点</button>
        </div>
      </div>
    </div>
  </div>
</template>
