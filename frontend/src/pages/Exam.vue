<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, type SubjectView } from '../api'
import { toast } from '../toast'

const router = useRouter()
const subjects = ref<SubjectView[]>([])
const subjectId = ref<number | null>(null)
const count = ref(50)
const duration = ref(150)
const loading = ref(true)
const starting = ref(false)

onMounted(async () => {
  try {
    const subs = await api.subjects()
    subjects.value = subs.filter((s) => s.choiceCount > 0)
    subjectId.value = subjects.value[0]?.id ?? null
  } catch (e) {
    toast((e as Error).message)
  } finally {
    loading.value = false
  }
})

const selected = computed(() => subjects.value.find((s) => s.id === subjectId.value) ?? null)
const realCount = computed(() => Math.min(count.value, selected.value?.choiceCount ?? 0))

async function start() {
  if (!subjectId.value) return
  if (!realCount.value) {
    toast('这门课还没有可刷的选择题')
    return
  }
  starting.value = true
  try {
    const session = await api.createSession({
      subjectId: subjectId.value,
      mode: 'EXAM',
      count: realCount.value,
      durationMinutes: Math.max(5, Math.min(duration.value, 300))
    })
    router.push(`/practice/${session.id}`)
  } catch (e) {
    toast((e as Error).message)
  } finally {
    starting.value = false
  }
}
</script>

<template>
  <div class="page">
    <h1 class="hello">模拟考试</h1>
    <p class="sub">按真实考试来：限时、交卷后统一判分，中途不给对错；自由选题可返回练习入口</p>

    <div v-if="loading" class="card empty">正在加载…</div>

    <template v-else>
      <div class="sec-title"><span>选学科</span></div>
      <div class="chips">
        <button
          v-for="s in subjects"
          :key="s.id"
          class="chip"
          :class="{ on: subjectId === s.id }"
          @click="subjectId = s.id"
        >
          <span class="dot" :style="{ background: s.color }"></span>
          {{ s.name }}
          <span class="muted">{{ s.choiceCount }} 题</span>
        </button>
        <div v-if="!subjects.length" class="muted">还没有可考的选择题</div>
      </div>

      <template v-if="selected">
        <div class="sec-title"><span>题量</span></div>
        <div class="card">
          <div class="chips">
            <button v-for="n in [20, 50, 100]" :key="n" class="chip" :class="{ on: count === n }" @click="count = n">
              {{ n }} 题
            </button>
            <input v-model.number="count" class="num" type="number" min="1" max="200" />
          </div>
        </div>

        <div class="sec-title"><span>考试时长</span></div>
        <div class="card">
          <div class="chips">
            <button
              v-for="m in [60, 90, 120, 150]"
              :key="m"
              class="chip"
              :class="{ on: duration === m }"
              @click="duration = m"
            >
              {{ m }} 分钟
            </button>
            <input v-model.number="duration" class="num" type="number" min="5" max="300" />
          </div>
        </div>

        <div class="card rules">
          <div class="rline">· 从「{{ selected.name }}」随机抽 {{ realCount }} 道选择题</div>
          <div class="rline">· 限时 {{ duration }} 分钟，到点自动交卷</div>
          <div class="rline">· 答题过程中不显示对错和解析，交卷后统一看成绩</div>
          <div class="rline">· 可以标记不确定的题，答题卡里能跳回去改</div>
        </div>

        <div class="go">
          <div class="muted" style="font-size: 13px">
            满分 100 分 · {{ realCount }} 题 · {{ duration }} 分钟
          </div>
          <button class="btn" :disabled="starting || !realCount" @click="start">
            {{ starting ? '正在准备试卷…' : '开始考试' }}
          </button>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
.card {
  padding: 16px;
  margin-bottom: 14px;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
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
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.num {
  width: 84px;
  padding: 6px 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
  font-size: 13px;
  font-family: inherit;
}
.num:focus {
  outline: none;
  border-color: var(--primary);
}
.rules {
  font-size: 13px;
  color: var(--muted);
  line-height: 1.9;
}
.go {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 18px;
}
</style>
