<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, TYPE_NAME, type StatsReport } from '../api'
import { toast } from '../toast'

const router = useRouter()
const data = ref<StatsReport | null>(null)
const days = ref(14)
const loading = ref(true)
const error = ref('')
const starting = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await api.statsReport(days.value)
  } catch (e) {
    data.value = null
    error.value = (e as Error).message
    toast((e as Error).message)
  } finally {
    loading.value = false
  }
}

async function setDays(d: number) {
  if (days.value === d) return
  days.value = d
  await load()
}

const daily = computed(() => data.value?.daily ?? [])

const chartData = computed(() => {
  const list = daily.value
  if (days.value !== 30) {
    return list.map((d) => ({ ...d, label: d.date.slice(5) }))
  }
  // 30 天按周汇总，避免横轴过密
  const groups: Array<{ date: string; label: string; answered: number; correct: number }> = []
  for (let i = 0; i < list.length; i += 7) {
    const chunk = list.slice(i, i + 7)
    if (!chunk.length) continue
    const start = chunk[0].date.slice(5)
    const end = chunk[chunk.length - 1].date.slice(5)
    const label = `${start}~${end}`
    groups.push({
      date: label,
      label,
      answered: chunk.reduce((a, d) => a + d.answered, 0),
      correct: chunk.reduce((a, d) => a + d.correct, 0)
    })
  }
  return groups
})

const maxDaily = computed(() => Math.max(1, ...chartData.value.map((d) => d.answered)))

const W = ref(620)
const H = 190
const PAD_L = 34
const PAD_B = 22

const chartEl = ref<SVGSVGElement | null>(null)
let ro: ResizeObserver | null = null

watch(chartEl, (el) => {
  ro?.disconnect()
  ro = null
  if (!el) return
  ro = new ResizeObserver((entries) => {
    const width = entries[0]?.contentRect.width ?? 0
    if (width > 0) W.value = Math.round(width)
  })
  ro.observe(el)
})

onBeforeUnmount(() => ro?.disconnect())

const bars = computed(() => {
  const n = chartData.value.length || 1
  const width = W.value
  const plotW = width - PAD_L - 10
  const slot = plotW / n
  const bw = Math.min(36, slot * 0.55)
  const gap = slot - bw
  const plotH = H - PAD_B - 14
  return chartData.value.map((d, i) => {
    const x = PAD_L + i * slot + gap / 2
    const h = (d.answered / axis.value.max) * plotH
    const ch = d.answered ? (d.correct / d.answered) * h : 0
    return {
      date: d.date,
      label: d.label,
      answered: d.answered,
      correct: d.correct,
      x,
      w: bw,
      y: H - PAD_B - h,
      h,
      correctH: ch
    }
  })
})

const axis = computed(() => {
  const max = maxDaily.value
  if (max <= 4) return { max: 4, step: 1 }
  const rough = max / 4
  const pow = Math.pow(10, Math.floor(Math.log10(rough)))
  const norm = rough / pow
  const step = (norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10) * pow
  return { max: Math.ceil(max / step) * step, step }
})

const gridLines = computed(() => {
  const plotH = H - PAD_B - 14
  const { max, step } = axis.value
  const out: Array<{ value: number; y: number }> = []
  for (let v = 0; v <= max + 1e-6; v += step) {
    out.push({ value: Math.round(v * 10) / 10, y: H - PAD_B - (v / max) * plotH })
  }
  return out
})

const subjects = computed(() =>
  (data.value?.subjects ?? []).filter((s) => s.choiceCount > 0 || s.answered > 0)
)

const types = computed(() => data.value?.types ?? [])

async function practiceWeak(subjectId: number) {
  if (starting.value) return
  starting.value = true
  try {
    const session = await api.startReview(20, subjectId)
    router.push(`/practice/${session.id}`)
  } catch (e) {
    toast((e as Error).message)
  } finally {
    starting.value = false
  }
}

function minutesText(m: number) {
  if (m < 60) return `${m} 分钟`
  return `${Math.floor(m / 60)} 小时 ${m % 60} 分`
}
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h1 class="hello">学习统计</h1>
        <p class="sub">累计指标统计全部学习数据；下方答题量按所选天数显示</p>
      </div>
      <div class="range">
        <button
          v-for="d in [7, 14, 30]"
          :key="d"
          type="button"
          class="tab"
          :class="{ on: days === d }"
          @click="setDays(d)"
        >
          {{ d }} 天
        </button>
      </div>
    </div>

    <div v-if="loading" class="card empty">正在统计…</div>
    <div v-else-if="error" class="card empty">
      统计加载失败：{{ error }}
      <div style="margin-top: 12px">
        <button class="btn sm" @click="load">重试</button>
      </div>
    </div>

    <template v-else-if="data">
      <div class="metrics card">
        <div class="metric">
          <div class="n">{{ data.overview.answered }}</div>
          <div class="l">累计答题</div>
        </div>
        <div class="metric">
          <div class="n" style="color: var(--primary)">{{ data.overview.accuracy }}<span>%</span></div>
          <div class="l">总正确率</div>
        </div>
        <div class="metric">
          <div class="n">{{ minutesText(data.overview.minutes) }}</div>
          <div class="l">学习时长</div>
        </div>
        <div class="metric">
          <div class="n" style="color: var(--warn)">{{ data.overview.streak }}<span>天</span></div>
          <div class="l">连续打卡</div>
        </div>
      </div>

      <div class="bank card">
        <div class="bcell"><b>{{ data.overview.choiceCount }}</b><span>可刷选择题</span></div>
        <div class="bcell"><b>{{ data.overview.cardCount }}</b><span>知识点</span></div>
        <div class="bcell"><b>{{ data.overview.pendingCount }}</b><span>待补答案</span></div>
        <button class="bcell metric-link" @click="router.push('/wrong')"><b style="color: var(--bad)">{{ data.overview.wrongCount }}</b><span>错题 · 去复习</span></button>
      </div>

      <div class="card">
        <div class="card-head">
          <span>近 {{ days }} 天答题量{{ days === 30 ? '（按周）' : '' }}</span>
          <span class="muted">今天 {{ data.overview.todayAnswered }} 题 · 正确 {{ data.overview.todayCorrect }} 题</span>
        </div>
        <svg ref="chartEl" class="chart" :viewBox="`0 0 ${W} ${H}`">
          <line
            v-for="g in gridLines"
            :key="g.y"
            :x1="PAD_L"
            :x2="W - 10"
            :y1="g.y"
            :y2="g.y"
            stroke="var(--line)"
            stroke-width="1"
          />
          <text
            v-for="g in gridLines"
            :key="'v' + g.value"
            :x="PAD_L - 6"
            :y="g.y + 3"
            font-size="10"
            text-anchor="end"
            fill="var(--muted)"
          >
            {{ g.value }}
          </text>
          <g v-for="(b, i) in bars" :key="b.date">
            <text
              v-if="b.answered > 0"
              :x="b.x + b.w / 2"
              :y="b.y - 6"
              font-size="10"
              text-anchor="middle"
              fill="var(--muted)"
            >
              {{ b.answered }}
            </text>
            <rect :x="b.x" :y="b.y" :width="b.w" :height="Math.max(b.h, 0)" rx="2" fill="var(--primary-soft)" />
            <rect
              v-if="b.correctH > 0"
              :x="b.x"
              :y="H - PAD_B - b.correctH"
              :width="b.w"
              :height="b.correctH"
              rx="2"
              fill="var(--primary)"
            />
            <text
              :x="b.x + b.w / 2"
              :y="H - 6"
              font-size="10"
              text-anchor="middle"
              fill="var(--muted)"
            >
              {{ b.label }}
            </text>
            <title>{{ b.date }}：答 {{ b.answered }}，对 {{ b.correct }}</title>
          </g>
        </svg>
        <div class="legend">
          <span><i style="background: var(--primary)"></i>答对</span>
          <span><i style="background: var(--primary-soft)"></i>答错/未对</span>
        </div>
      </div>

      <div class="sec-title"><span>各学科</span></div>
      <div class="card list">
        <div v-for="s in subjects" :key="s.subjectId" class="srow">
          <div class="sname">
            <span class="dot" :style="{ background: s.color }"></span>
            {{ s.name }}
          </div>
          <div class="sbar">
            <i :style="{ width: Math.min(100, s.accuracy) + '%', background: s.color }"></i>
          </div>
          <div class="snum">
            <b>{{ s.accuracy }}%</b>
            <span class="muted">已练 {{ s.answered }}/{{ s.choiceCount }}</span>
            <span v-if="s.pending" class="muted">待补 {{ s.pending }}</span>
            <button v-if="s.answered > 0 && s.accuracy < 70" class="mini review" :disabled="starting" @click="practiceWeak(s.subjectId)">练薄弱题</button>
          </div>
        </div>
        <div v-if="!subjects.length" class="muted" style="font-size: 13px">还没有练习记录</div>
      </div>

      <div class="sec-title"><span>题型</span></div>
      <div class="card types">
        <div v-for="t in types" :key="t.type" class="tchip">
          <div class="tname">{{ TYPE_NAME[t.type] ?? t.type }}</div>
          <div class="tnum">{{ t.accuracy }}%</div>
          <div class="tmuted">答 {{ t.answered }} 次</div>
        </div>
        <div v-if="!types.length" class="muted" style="font-size: 13px">还没有作答记录</div>
      </div>

      <div class="foot">
        <button class="btn ghost" @click="router.push('/')">回首页</button>
        <button class="btn ghost" @click="router.push('/wrong')">看错题本</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}
.range {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #f4f5fb;
  box-shadow: var(--shadow-sm);
}
.tab {
  height: 32px;
  padding: 0 14px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: var(--muted);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: color .15s, background .15s, box-shadow .15s;
}
.tab:hover {
  color: var(--primary);
  background: rgba(255, 255, 255, .72);
}
.tab.on {
  color: var(--primary);
  background: var(--card);
  font-weight: 700;
  box-shadow: 0 1px 2px rgba(19, 26, 51, .08), 0 5px 12px -8px rgba(19, 26, 51, .3);
}
.tab:focus-visible {
  outline: none;
  box-shadow: var(--ring);
}
.metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.card.metrics {
  margin-bottom: 24px;
}
.metric .n {
  font-size: 22px;
  font-weight: 700;
}
.metric .n span {
  font-size: 13px;
  font-weight: 500;
  margin-left: 2px;
}
.metric .l {
  font-size: 12px;
  color: var(--muted);
  margin-top: 2px;
}
.bank {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.bcell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
  color: var(--muted);
}
.metric-link {
  border: 0;
  padding: 0;
  text-align: left;
  cursor: pointer;
  font: inherit;
  background: transparent;
}
.metric-link:hover span { color: var(--primary); }
.bcell b {
  font-size: 18px;
  color: var(--fg);
}
.card {
  margin-bottom: 20px;
  padding: 16px;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 13.5px;
  font-weight: 600;
}
.chart {
  display: block;
  width: 100%;
  height: 190px;
  margin-top: 8px;
}
.legend {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--muted);
  margin-top: 6px;
}
.legend i {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 2px;
  margin-right: 5px;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.srow {
  display: grid;
  grid-template-columns: 160px 1fr auto;
  gap: 12px;
  align-items: center;
  font-size: 13px;
}
.sname {
  display: flex;
  align-items: center;
  gap: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: 0 0 auto;
}
.sbar {
  height: 7px;
  background: var(--line);
  border-radius: 999px;
  overflow: hidden;
}
.sbar i {
  display: block;
  height: 100%;
  border-radius: 999px;
}
.snum {
  display: flex;
  gap: 10px;
  align-items: baseline;
  font-size: 12px;
  white-space: nowrap;
}
.snum .mini { padding: 4px 9px; border-radius: 8px; font-size: 11px; }
.types {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.tchip {
  min-width: 108px;
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
}
.tname {
  font-size: 12.5px;
  color: var(--muted);
}
.tnum {
  font-size: 20px;
  font-weight: 700;
  color: var(--primary);
}
.tmuted {
  font-size: 11.5px;
  color: var(--muted);
}
.foot {
  display: flex;
  gap: 10px;
  margin-top: 18px;
}
@media (max-width: 640px) {
  .metrics {
    grid-template-columns: repeat(2, 1fr);
  }
  .srow {
    grid-template-columns: 1fr;
    gap: 6px;
  }
  .snum {
    justify-content: space-between;
  }
}
</style>
