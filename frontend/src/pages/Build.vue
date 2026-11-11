<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, TYPE_NAME, type BuildOptions, type SubjectView } from '../api'
import { toast } from '../toast'

const router = useRouter()

const subjects = ref<SubjectView[]>([])
const subjectId = ref<number | null>(null)
const options = ref<BuildOptions | null>(null)
const pickedTypes = ref<string[]>([])
const pickedYears = ref<number[]>([])
const chapterId = ref<number | null>(null)
const range = ref<'all' | 'wrong' | 'new'>('all')
const count = ref(20)
const loading = ref(true)
const starting = ref(false)

onMounted(async () => {
  try {
    const subs = await api.subjects()
    subjects.value = subs.filter((s) => s.choiceCount > 0 || s.pendingCount > 0)
    const first = subjects.value.find((s) => s.choiceCount > 0) ?? subjects.value[0]
    if (first) {
      subjectId.value = first.id
      await loadOptions()
    }
  } catch (e) {
    toast((e as Error).message)
  } finally {
    loading.value = false
  }
})

watch(subjectId, async (id) => {
  if (id) await loadOptions()
})

async function loadOptions() {
  if (!subjectId.value) return
  try {
    const o = await api.buildOptions(subjectId.value)
    options.value = o
    pickedTypes.value = o.types.map((t) => t.type)
    pickedYears.value = []
    chapterId.value = null
    range.value = 'all'
  } catch (e) {
    toast((e as Error).message)
  }
}

function toggleType(t: string) {
  const i = pickedTypes.value.indexOf(t)
  if (i >= 0) pickedTypes.value.splice(i, 1)
  else pickedTypes.value.push(t)
}

function toggleYear(y: number) {
  const i = pickedYears.value.indexOf(y)
  if (i >= 0) pickedYears.value.splice(i, 1)
  else pickedYears.value.push(y)
}

const typeSum = computed(() => {
  if (!options.value) return 0
  return options.value.types
    .filter((t) => pickedTypes.value.includes(t.type))
    .reduce((a, t) => a + t.count, 0)
})

const estimate = computed(() => {
  if (!options.value) return 0
  const chapter = options.value.chapters.find((c) => c.id === chapterId.value)
  const base = range.value === 'wrong'
    ? (chapter?.wrongCount ?? options.value.wrongCount)
    : range.value === 'new'
      ? (chapter?.newCount ?? options.value.newCount)
      : (chapter?.count ?? typeSum.value)
  return Math.min(typeSum.value, base)
})

const yearSum = computed(() => {
  if (!options.value || !pickedYears.value.length) return 0
  return options.value.years
    .filter((y) => pickedYears.value.includes(y.year))
    .reduce((a, y) => a + y.count, 0)
})

const finalEstimate = computed(() => (pickedYears.value.length ? Math.min(estimate.value, yearSum.value) : estimate.value))

const realCount = computed(() => Math.min(count.value, finalEstimate.value))

async function start() {
  if (!subjectId.value) return
  if (!pickedTypes.value.length) {
    toast('至少选一种题型')
    return
  }
  if (finalEstimate.value === 0) {
    toast('没有符合条件的题，换个范围试试')
    return
  }
  starting.value = true
  try {
    const session = await api.createSession({
      subjectId: subjectId.value,
      mode: 'CUSTOM',
      count: realCount.value,
      types: pickedTypes.value,
      years: pickedYears.value,
      onlyWrong: range.value === 'wrong',
      onlyNew: range.value === 'new',
      shuffle: true
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
    <div class="page-head">
      <div>
        <h1 class="hello">组卷练习</h1>
        <p class="sub">自由练习、错题和年份筛选；需要限时整套考试，可切换到模拟考试</p>
      </div>
      <button type="button" class="entry-link" @click="router.push('/exam')">
        <span class="e-ico">⏱</span>
        模拟考试
      </button>
    </div>

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
          <span class="muted">{{ s.choiceCount }}</span>
        </button>
        <div v-if="!subjects.length" class="muted">还没有可刷的题</div>
      </div>

      <template v-if="options && options.choiceCount > 0">
        <div v-if="options.chapters.length" class="sec-title"><span>章节</span></div>
        <div v-if="options.chapters.length" class="card">
          <div class="chips">
            <button class="chip" :class="{ on: chapterId === null }" @click="chapterId = null">全部章节 <span class="muted">{{ typeSum }}</span></button>
            <button v-for="c in options.chapters" :key="c.id" class="chip" :class="{ on: chapterId === c.id }" @click="chapterId = c.id">
              {{ c.name }} <span class="muted">{{ c.count }}</span>
            </button>
          </div>
        </div>
        <div class="sec-title"><span>题型</span></div>
        <div class="card pick">
          <label v-for="t in options.types" :key="t.type" class="topt" :class="{ on: pickedTypes.includes(t.type) }">
            <input
              type="checkbox"
              :checked="pickedTypes.includes(t.type)"
              @change="toggleType(t.type)"
            />
            {{ TYPE_NAME[t.type] ?? t.type }}
            <span class="muted">{{ t.count }} 题</span>
          </label>
        </div>

        <div class="sec-title"><span>范围</span></div>
        <div class="card">
          <div class="chips">
            <button class="chip" :class="{ on: range === 'all' }" @click="range = 'all'">全部题目</button>
            <button class="chip" :class="{ on: range === 'wrong' }" @click="range = 'wrong'">
              只练错题 <span class="muted">{{ options.wrongCount }}</span>
            </button>
            <button class="chip" :class="{ on: range === 'new' }" @click="range = 'new'">
              只练没做过 <span class="muted">{{ options.newCount }}</span>
            </button>
          </div>

          <div v-if="options.years.length" class="years">
            <span class="muted" style="font-size: 12.5px">年份（不选=全部）</span>
            <div class="chips" style="margin-top: 8px">
              <button
                v-for="y in options.years"
                :key="y.year"
                class="chip small"
                :class="{ on: pickedYears.includes(y.year) }"
                @click="toggleYear(y.year)"
              >
                {{ y.year }} <span class="muted">{{ y.count }}</span>
              </button>
            </div>
          </div>
        </div>

        <div class="sec-title"><span>题量</span></div>
        <div class="card">
          <div class="chips">
            <button v-for="n in [10, 20, 30, 50]" :key="n" class="chip" :class="{ on: count === n }" @click="count = n">
              {{ n }} 题
            </button>
            <input v-model.number="count" class="num" type="number" min="1" max="200" />
          </div>
        </div>

        <div class="go">
          <div class="muted" style="font-size: 13px">
            预计可组 <b>{{ finalEstimate }}</b> 题 · 本次练 <b>{{ realCount }}</b> 题
            <span v-if="realCount < count">（题量不足，按可用的来）</span>
          </div>
          <button class="btn" :disabled="starting || finalEstimate === 0" @click="start">
            {{ starting ? '正在组卷…' : '开始练习' }}
          </button>
        </div>
      </template>

      <div v-else-if="options" class="card empty">
        这门课还没有可刷的选择题，可以去「补答案」看看待补的题。
      </div>
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
.chip.small {
  padding: 4px 10px;
  font-size: 12px;
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
.pick {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.pick .topt {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  font-size: 13.5px;
  cursor: pointer;
}
.pick .topt.on {
  border-color: var(--primary);
  background: var(--primary-soft);
}
.pick input {
  accent-color: var(--primary);
}
.years {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
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
.go {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 18px;
}
.go b {
  color: var(--primary);
}
.link {
  color: var(--primary);
  cursor: pointer;
}
.link:hover {
  text-decoration: underline;
}
</style>
