<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  api,
  getActiveMajor,
  majorStorageKey,
  setActiveMajor,
  type MajorCatalogView,
  type MajorCourseView,
  type SubjectView
} from '../api'
import { toast } from '../toast'

const route = useRoute()
const router = useRouter()

const editMode = computed(() => route.path === '/plan')
const setupUserId = localStorage.getItem('quizmate.userId') || 'current'

function deferSetup() {
  localStorage.setItem(`quizmate.setupDeferred.${setupUserId}`, '1')
  router.replace('/')
}

const PROVINCES = [
  '北京', '天津', '河北', '山西', '内蒙古', '辽宁', '吉林', '黑龙江',
  '上海', '江苏', '浙江', '安徽', '福建', '江西', '山东', '河南',
  '湖北', '湖南', '广东', '广西', '海南', '重庆', '四川', '贵州',
  '云南', '西藏', '陕西', '甘肃', '青海', '宁夏', '新疆', '香港',
  '澳门', '台湾', '其他 / 海外'
]

const LEVELS = ['本科', '专科']

interface Row {
  id?: number
  name: string
  code: string
  selected: boolean
  examDate?: string
  existing?: boolean
  questionCount?: number
  choiceCount?: number
  source?: 'official' | 'existing' | 'custom'
}

const step = ref<1 | 2 | 3>(1)
const loading = ref(true)
const saving = ref(false)

const province = ref('')
const level = ref('本科')
const majorName = ref('')
const majorCode = ref('')
const manualName = ref('')
const manualCode = ref('')
const catalogPicked = ref(false)
const originalMajorName = ref('')
const originalMajorCode = ref('')
const originalProvince = ref('')
const originalLevel = ref('本科')
const majorId = ref<number | null>(null)
const majorQuestionCount = ref(0)

const courseLoading = ref(false)
const courseInfo = ref<MajorCourseView | null>(null)
const rows = ref<Row[]>([])
const search = ref('')
const commonDate = ref(defaultExamDate())
const perSubject = ref(false)

const catalog = ref<MajorCatalogView | null>(null)
const catalogLoading = ref(false)
const catalogError = ref('')
const catalogSearch = ref('')

const filteredCatalog = computed(() => {
  const items = catalog.value?.items ?? []
  const q = catalogSearch.value.trim().toLowerCase()
  if (!q) return items
  return items.filter(
    (i) => i.name.toLowerCase().includes(q) || (i.code || '').toLowerCase().includes(q)
  )
})

const catalogSourceLabel = computed(() =>
  catalog.value?.scope === 'province' ? '本省考试院官方目录' : '全国标准专业目录'
)

const catalogCaution = computed(() => {
  if (catalog.value?.scope !== 'province') return ''
  const v = catalog.value?.version || ''
  if (/课程安排|考试安排|开考计划|报考简章|课程时间安排/.test(v)) {
    return '该目录依据官方开考 / 课程安排整理，未单独标注停考过渡专业，报考前请以省考试院最新公告为准。'
  }
  return ''
})

const courseNote = computed(() => {
  if (courseLoading.value) return '正在读取官方课程…'
  const info = courseInfo.value
  if (!info) return ''
  if (info.scope === 'province') {
    const ver = info.version || '本省官方课程目录'
    const date = info.updatedAt ? ` · ${info.updatedAt}` : ''
    return `${ver}${date} · 共 ${info.count} 门`
  }
  if (catalog.value?.scope === 'province') {
    return '该专业已收录于本省官方开考专业目录；官方完整课程设置尚未收录，请按省考试院专业计划手动添加应考学科。'
  }
  return '本省官方专业目录尚未收录，当前专业来自全国标准目录；完整课程设置也未收录，请手动添加并核对省考试院公告。'
})

const selectedRows = computed(() => rows.value.filter((r) => r.selected && r.name.trim()))
const selectedCount = computed(() => selectedRows.value.length)

const filteredRows = computed(() => {
  const q = search.value.trim().toLowerCase()
  if (!q) return rows.value
  return rows.value.filter(
    (r) => r.name.toLowerCase().includes(q) || (r.code || '').toLowerCase().includes(q)
  )
})

const allChecked = computed({
  get: () => filteredRows.value.length > 0 && filteredRows.value.every((r) => r.selected),
  set: (v: boolean) => filteredRows.value.forEach((r) => (r.selected = v))
})

const quickDates = computed(() => {
  const today = startOfToday()
  const out: { label: string; date: string }[] = []
  for (let y = today.getFullYear(); y <= today.getFullYear() + 2; y++) {
    for (const m of [4, 10]) {
      const d = new Date(y, m - 1, 15)
      if (d >= today) out.push({ label: `${y} 年 ${m} 月`, date: fmtDate(d) })
    }
  }
  return out.slice(0, 4)
})

const daysLeft = computed(() => daysTo(commonDate.value))
const dateText = computed(() => formatCn(commonDate.value))

function onRegionChange() {
  // 换省份 / 层次后，旧专业不一定在新目录里，先清空让用户重新选择
  majorName.value = ''
  majorCode.value = ''
  manualName.value = ''
  manualCode.value = ''
  catalogPicked.value = false
  loadCatalog()
}

function pickCatalogItem(item: { code: string; name: string }) {
  majorName.value = item.name
  majorCode.value = item.code
  manualName.value = ''
  manualCode.value = ''
  catalogPicked.value = true
}

function applyManualMajor() {
  if (catalogPicked.value) return
  majorName.value = manualName.value.trim()
  majorCode.value = manualCode.value.trim()
}

function clearMajor() {
  majorName.value = ''
  majorCode.value = ''
  manualName.value = ''
  manualCode.value = ''
  catalogPicked.value = false
}

onMounted(load)

async function loadCatalog() {
  catalogLoading.value = true
  catalogError.value = ''
  try {
    catalog.value = await api.majorCatalog(province.value, level.value)
    catalogSearch.value = ''
  } catch (e) {
    catalog.value = null
    catalogError.value = (e as Error).message
  } finally {
    catalogLoading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    if (editMode.value) {
      const [majors, subjects] = await Promise.all([api.majors(), api.subjects()])
      const active = majors.find((m) => m.id === getActiveMajor()) ?? majors[0]
      if (active) {
        majorId.value = active.id
        majorName.value = active.name
        originalMajorName.value = active.name
        majorCode.value = active.code ?? ''
        originalMajorCode.value = active.code ?? ''
        province.value = active.province ?? ''
        originalProvince.value = active.province ?? ''
        level.value = active.level ?? '本科'
        originalLevel.value = active.level ?? '本科'
        catalogPicked.value = !!active.name
        manualName.value = ''
        manualCode.value = ''
      }
      rows.value = subjects.map(toRow)
      majorQuestionCount.value = subjects.reduce((n, s) => n + s.questionCount, 0)
      const dates = subjects
        .map((s) => s.examDate)
        .filter((d): d is string => !!d)
        .sort()
      if (dates.length) commonDate.value = dates[0]
      // 老计划里各科日期不同：默认保持「分别设置」，避免保存时被统一日期覆盖
      if (new Set(dates).size > 1) perSubject.value = true
      // 从考试计划进入时，先展示已保存的地区 / 专业信息
      step.value = 1
      if (majorName.value) await loadCourseInfo(false)
    }
    await loadCatalog()
  } catch (e) {
    toast((e as Error).message)
  } finally {
    loading.value = false
  }
}

function toRow(s: SubjectView): Row {
  return {
    id: s.id,
    name: s.name,
    code: s.code ?? '',
    selected: !!s.examDate,
    examDate: s.examDate ?? undefined,
    existing: true,
    questionCount: s.questionCount,
    choiceCount: s.choiceCount,
    source: 'existing'
  }
}

async function toSubjects() {
  const name = majorName.value.trim()
  if (!name) {
    toast('先在下面的官方目录里选择专业')
    return
  }
  majorName.value = name
  const changed =
    name !== originalMajorName.value ||
    majorCode.value !== originalMajorCode.value ||
    province.value !== originalProvince.value ||
    level.value !== originalLevel.value
  // 换专业/省份/层次后，先清空编辑器里的旧学科，避免旧专业课程混进新专业
  if (changed) {
    rows.value = []
    majorQuestionCount.value = 0
  }
  step.value = 2
  if (changed) await loadCourseInfo(true)
  originalMajorName.value = name
  originalMajorCode.value = majorCode.value
  originalProvince.value = province.value
  originalLevel.value = level.value
}

async function loadCourseInfo(merge: boolean) {
  courseLoading.value = true
  courseInfo.value = null
  try {
    const res = await api.majorCourses({
      province: province.value,
      level: level.value,
      code: majorCode.value,
      name: majorName.value
    })
    courseInfo.value = res
    if (!merge || res.scope !== 'province') return
    for (const c of res.courses) {
      if (!rows.value.some((r) => sameName(r, c.name, c.code))) {
        rows.value.push({
          name: c.name,
          code: c.code ?? '',
          selected: false,
          source: 'official'
        })
      }
    }
  } catch (e) {
    toast((e as Error).message)
  } finally {
    courseLoading.value = false
  }
}

function sameName(row: Row, name: string, code?: string) {
  if (row.name.trim() === name.trim()) return true
  return !!code && !!row.code && row.code.trim() === code.trim()
}

function addCustom() {
  rows.value.push({ name: '', code: '', selected: true, source: 'custom' })
}

function removeRow(i: number) {
  const row = rows.value[i]
  if (row.existing) {
    row.selected = false
    return
  }
  rows.value.splice(i, 1)
}

function goNext() {
  if (step.value === 1) {
    toSubjects()
    return
  }
  if (step.value === 2) {
    if (!selectedCount.value) {
      toast('先勾选这次要考的学科')
      return
    }
    step.value = 3
  }
}

function goBack() {
  if (step.value === 2 && editMode.value) {
    router.push('/')
    return
  }
  if (step.value > 1) step.value = (step.value - 1) as 1 | 2 | 3
}

function chooseCommonDate(d: string) {
  commonDate.value = d
  if (perSubject.value) selectedRows.value.forEach((r) => (r.examDate = d))
}

function togglePerSubject(v: boolean) {
  perSubject.value = v
  if (v) selectedRows.value.forEach((r) => (r.examDate = r.examDate || commonDate.value))
}

async function finish() {
  if (!selectedCount.value) {
    toast('至少选择一门这次要考的学科')
    return
  }
  saving.value = true
  try {
    const res = await api.completeSetup({
      majorId: editMode.value ? majorId.value ?? undefined : undefined,
      majorName: majorName.value.trim(),
      majorCode: majorCode.value.trim() || undefined,
      province: province.value || undefined,
      level: level.value,
      subjects: rows.value
        .filter((r) => r.selected || r.existing)
        .filter((r) => r.name.trim())
        .map((r) => ({
          id: r.id,
          name: r.name.trim(),
          code: r.code.trim() || undefined,
          selected: r.selected,
          examDate: r.selected
            ? perSubject.value
              ? r.examDate || commonDate.value
              : commonDate.value
            : undefined
        }))
    })
    setActiveMajor(res.majorId)
    localStorage.setItem(majorStorageKey(), String(res.majorId))
    window.dispatchEvent(new CustomEvent('quizmate:majors', { detail: { prefer: res.majorId } }))
    toast(editMode.value ? '考试计划已更新' : '设置完成，开始备考吧')
    router.replace('/')
  } catch (e) {
    toast((e as Error).message)
  } finally {
    saving.value = false
  }
}

function daysTo(date: string) {
  if (!date) return 0
  const d = new Date(date + 'T00:00:00')
  return Math.round((d.getTime() - startOfToday().getTime()) / 86400000)
}

function formatCn(date: string) {
  if (!date) return '未设置'
  const d = new Date(date + 'T00:00:00')
  const week = '日一二三四五六'[d.getDay()]
  return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日 · 周${week}`
}

function defaultExamDate() {
  const today = startOfToday()
  for (let y = today.getFullYear(); y <= today.getFullYear() + 2; y++) {
    for (const m of [4, 10]) {
      const d = new Date(y, m - 1, 15)
      if (d >= today) return fmtDate(d)
    }
  }
  return fmtDate(new Date(today.getFullYear() + 1, 3, 15))
}

function startOfToday() {
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  return d
}

function fmtDate(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(
    d.getDate()
  ).padStart(2, '0')}`
}
</script>

<template>
  <div class="setup-page">
    <header class="setup-head">
      <div class="brand">
        <span class="logo" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H11v17H6.5A2.5 2.5 0 0 0 4 22V5.5Z"/><path d="M20 5.5A2.5 2.5 0 0 0 17.5 3H13v17h4.5A2.5 2.5 0 0 1 20 22V5.5Z"/><path d="m8 9 1.5 1.5L12 8"/></svg></span>
        <div>
          <b>{{ editMode ? '考试计划' : '开始你的自考' }}</b>
          <span>先确定专业和考期，之后只学这次要考的</span>
        </div>
      </div>
      <button v-if="!editMode" class="head-link" @click="deferSetup">稍后设置</button>
      <button v-if="editMode" class="head-link" @click="router.push('/')">返回首页</button>
    </header>

    <main class="setup-main">
      <div v-if="loading" class="card empty">正在加载…</div>

      <template v-else>
        <div class="steps">
          <div
            v-for="n in 3"
            :key="n"
            class="step-dot"
            :class="{ active: step === n, done: step > n }"
          >
            <i>{{ step > n ? '✓' : n }}</i>
            <span>{{ n === 1 ? '专业' : n === 2 ? '学科' : '考试日期' }}</span>
          </div>
        </div>

        <!-- 第 1 步：地区 + 专业 -->
        <section v-if="step === 1" class="card panel">
          <div class="panel-head">
            <h1>你是哪里的考生，报的什么专业？</h1>
            <p>不同省份、不同专业的考试科目不一样。填一次，以后只显示你自己的课。</p>
          </div>

          <div class="form-row">
            <label class="field">
              <span>考试地区</span>
              <select v-model="province" class="inp" @change="onRegionChange">
                <option value="">先不选</option>
                <option v-for="p in PROVINCES" :key="p" :value="p">{{ p }}</option>
              </select>
              <em>用于按省份帮你找考试科目，之后可以改</em>
            </label>

            <label class="field level">
              <span>报考层次</span>
              <select v-model="level" class="inp" @change="onRegionChange">
                <option v-for="l in LEVELS" :key="l" :value="l">{{ l }}</option>
              </select>
            </label>
          </div>

          <div class="picked" :class="{ empty: !majorName }">
            <template v-if="majorName">
              <div class="picked-main">
                <span class="picked-label">已选专业</span>
                <b>{{ majorName }}</b>
                <code v-if="majorCode">{{ majorCode }}</code>
              </div>
              <em v-if="editMode">换专业或省份后，学科会按新目录重新匹配；旧学科和题库仍保留，不会被删除。</em>
            </template>
            <span v-else>请从下方官方目录选择专业</span>
          </div>

          <div class="catalog">
            <div class="catalog-head">
              <div class="catalog-title">
                <span class="catalog-badge" :class="{ national: catalog?.scope !== 'province' }">
                  {{ catalogSourceLabel }}
                </span>
                <b>{{ catalog?.province || '全国' }}</b>
                <span class="muted">{{ level }}</span>
                <span v-if="catalog" class="muted">· {{ catalog.count }} 个专业</span>
              </div>
              <a
                v-if="catalog?.sourceUrl"
                :href="catalog.sourceUrl"
                target="_blank"
                rel="noreferrer"
                class="catalog-src"
              >
                查看官方来源 ↗
              </a>
            </div>
            <p class="catalog-note">
              {{ catalog?.version || '正在读取专业目录…' }}
              <template v-if="catalog?.updatedAt"> · {{ catalog.updatedAt }}</template>
              <template v-if="catalog?.scope === 'national'">
                。本省官方目录尚未收录，以下为教育部全国标准专业目录，具体开考专业请以省考试院为准。
              </template>
              <template v-else-if="catalog?.note"> {{ catalog.note }}</template>
              <template v-if="catalogCaution"> {{ catalogCaution }}</template>
            </p>

            <div v-if="catalogLoading" class="catalog-loading">正在加载专业目录…</div>
            <div v-else-if="catalogError" class="catalog-error">{{ catalogError }}</div>
            <template v-else>
              <input
                v-model="catalogSearch"
                class="inp catalog-search"
                placeholder="搜索专业名称 / 专业代码"
              />
              <div class="catalog-list">
                <button
                  v-for="item in filteredCatalog"
                  :key="item.code + item.name"
                  class="catalog-item"
                  :class="{ on: majorCode === item.code && majorName === item.name }"
                  @click="pickCatalogItem(item)"
                >
                  <span class="ci-code">{{ item.code || '—' }}</span>
                  <span class="ci-name">{{ item.name }}</span>
                </button>
              </div>
              <div v-if="!filteredCatalog.length" class="catalog-empty">
                没有匹配的专业，可以在下面手动填写专业名称。
              </div>
            </template>
          </div>

          <div class="manual-major" :class="{ disabled: catalogPicked }">
            <div class="manual-title">
              <span>目录里没有这个专业？手动填写</span>
              <button v-if="catalogPicked" class="link-btn" @click="clearMajor">改为手动填写</button>
            </div>
            <div class="manual-row">
              <input
                v-model="manualName"
                class="inp"
                :disabled="catalogPicked"
                :placeholder="catalogPicked ? '已从目录选择专业' : '专业名称，例如：汉语言文学'"
                @input="applyManualMajor"
              />
              <input
                v-model="manualCode"
                class="inp code"
                :disabled="catalogPicked"
                :placeholder="catalogPicked ? '代码来自目录' : '专业代码（可选）'"
                @input="applyManualMajor"
              />
            </div>
          </div>

          <footer class="panel-foot">
            <span class="muted">第 1 步 / 共 3 步</span>
            <button class="btn lg" :disabled="!majorName" @click="toSubjects">
              下一步：挑这次要考的学科
            </button>
          </footer>
        </section>

        <!-- 第 2 步：这次考哪几门 -->
        <section v-else-if="step === 2" class="card panel">
          <div class="panel-head">
            <div class="ph-top">
              <div>
                <h1>这次考试，你准备考哪几门？</h1>
                <p>
                  {{ province || '未选地区' }} · {{ level }} · {{ majorName }}<span v-if="editMode"> · 已有 {{ majorQuestionCount }} 道题</span>
                </p>
              </div>
              <div class="ph-actions">
                <button v-if="editMode" class="btn ghost sm" @click="step = 1">修改地区 / 专业</button>
              </div>
            </div>
            <p class="tip">
              一般一个考期报 2–4 门。没勾的科目以后可以在「考试计划」里再加，不会删除已有题库。
            </p>
            <p v-if="courseNote" class="course-source">
              {{ courseNote }}
              <a v-if="courseInfo?.sourceUrl" :href="courseInfo.sourceUrl" target="_blank" rel="noreferrer">官方来源</a>
              <button
                v-if="courseInfo?.scope === 'province'"
                class="link-btn"
                :disabled="courseLoading"
                @click="loadCourseInfo(true)"
              >
                补齐官方课程
              </button>
            </p>
            <p v-if="courseInfo && courseInfo.scope !== 'province' && !rows.length" class="list-empty course-empty">
              还没有学科。官方完整课程设置暂未收录，请先手动添加这次要考的学科。
            </p>
          </div>

          <div class="list-tools">
            <input v-model="search" class="inp search" placeholder="搜索学科名称 / 课程代码" />
            <label class="check-all">
              <input v-model="allChecked" type="checkbox" />
              全选当前
            </label>
            <span class="sel-count">已选 <b>{{ selectedCount }}</b> 门</span>
          </div>

          <div class="subject-list">
            <div v-for="(r, i) in filteredRows" :key="i" class="subject-row" :class="{ on: r.selected }">
              <input v-model="r.selected" type="checkbox" class="ck" />
              <input v-model="r.code" class="inp code" placeholder="代码" />
              <input v-model="r.name" class="inp name" placeholder="学科名称" />
              <span v-if="r.existing && (r.questionCount || 0) > 0" class="have">
                已有 {{ r.questionCount }} 题
              </span>
              <span v-else-if="r.source === 'official'" class="tag official">官方</span>
              <button class="row-del" title="从本次计划里移除" @click="removeRow(rows.indexOf(r))">✕</button>
            </div>
          </div>

          <div v-if="!filteredRows.length && !(courseInfo && courseInfo.scope !== 'province' && !rows.length)" class="list-empty">
            没有匹配的学科。你可以手动添加。
          </div>

          <button class="add-row" @click="addCustom">+ 手动添加一门学科</button>

          <footer class="panel-foot">
            <button class="btn ghost" @click="goBack">上一步</button>
            <div class="foot-right">
              <span class="muted">第 2 步 / 共 3 步</span>
              <button class="btn lg" :disabled="!selectedCount" @click="goNext">
                下一步：定考试日期
              </button>
            </div>
          </footer>
        </section>

        <!-- 第 3 步：什么时候考试 -->
        <section v-else class="card panel date-panel">
          <div class="panel-head date-panel-head">
            <span class="panel-kicker">第 3 步 · 考试时间</span>
            <h1>什么时候考试？</h1>
            <p>自考一般在 4 月和 10 月，具体日期以本省考试院公布为准。</p>
          </div>

          <div class="date-block">
            <div class="date-block-head">
              <b>快捷选择考期</b>
              <span>常见的 4 月 / 10 月考期</span>
            </div>
            <div class="date-quick">
              <button
                v-for="q in quickDates"
                :key="q.date"
                class="chip date-chip"
                :class="{ on: commonDate === q.date }"
                @click="chooseCommonDate(q.date)"
              >
                <span class="dc-month">{{ q.label.replace(' 年 ', ' / ').replace(' 月', '') }}</span>
                <span class="dc-day">15</span>
              </button>
            </div>
          </div>

          <div class="date-block">
            <div class="date-block-head">
              <b>自定义考试日期</b>
              <span>如果本省日期不是常规考期，可以直接选择</span>
            </div>
            <div class="date-row">
              <label class="field">
                <span>考试日期</span>
                <input v-model="commonDate" type="date" class="inp big" @change="chooseCommonDate(commonDate)" />
              </label>
              <div class="countdown">
                <span>距离考试</span>
                <b>{{ daysLeft >= 0 ? daysLeft : 0 }}</b>
                <em>{{ daysLeft >= 0 ? '天' : '考试已过' }}</em>
              </div>
            </div>
          </div>

          <div class="summary">
            <div class="sum-head">
              <div class="sum-title">这次考试安排</div>
              <span class="sum-count">{{ selectedRows.length }} 门学科</span>
            </div>
            <div class="sum-date">{{ dateText }}</div>
            <div class="sum-subjects">
              <div v-for="r in selectedRows" :key="r.name" class="sum-subject">
                {{ r.name }}
              </div>
            </div>
          </div>

          <label class="per-subject">
            <input
              type="checkbox"
              :checked="perSubject"
              @change="togglePerSubject(($event.target as HTMLInputElement).checked)"
            />
            <span>不同科目的考试日期不一样，我要分别设置</span>
          </label>

          <div v-if="perSubject" class="per-list">
            <div v-for="r in selectedRows" :key="r.name" class="per-row">
              <span>{{ r.name }}</span>
              <input v-model="r.examDate" type="date" class="inp" />
            </div>
          </div>

          <footer class="panel-foot date-foot">
            <button class="btn ghost" @click="goBack">上一步</button>
            <div class="foot-right">
              <span class="muted">第 3 步 / 共 3 步</span>
              <button class="btn lg" :disabled="saving" @click="finish">
                {{ saving ? '保存中…' : editMode ? '保存考试计划' : '完成设置，开始备考' }}
              </button>
            </div>
          </footer>
        </section>
      </template>
    </main>
  </div>
</template>

<style scoped>
.setup-page {
  min-height: 100vh;
  padding: 26px 20px 60px;
}
.setup-head {
  max-width: 860px;
  margin: 0 auto 22px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.brand .logo {
  width: 40px;
  height: 40px;
  border-radius: 13px;
  background: linear-gradient(135deg, #6d5efc, #a855f7);
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 17px;
  font-weight: 800;
  box-shadow: 0 10px 22px -10px rgba(109, 94, 252, 0.95);
}
.brand b {
  display: block;
  font-size: 17px;
  letter-spacing: -0.01em;
}
.brand span {
  display: block;
  font-size: 12px;
  color: var(--muted);
}
.head-link {
  height: 34px;
  padding: 0 13px;
  border-radius: 10px;
  border: 1px solid var(--line);
  background: var(--card);
  color: var(--muted);
  font-size: 12.5px;
  font-weight: 600;
}
.head-link:hover {
  color: var(--primary);
  border-color: var(--primary);
}
.setup-main {
  max-width: 860px;
  margin: 0 auto;
}
.steps {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 26px;
  margin-bottom: 18px;
}
.step-dot {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 12.5px;
  font-weight: 600;
}
.step-dot i {
  width: 25px;
  height: 25px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-style: normal;
  font-size: 12px;
  background: #eceef6;
  color: var(--muted);
}
.step-dot.active {
  color: var(--primary);
}
.step-dot.active i {
  background: var(--primary);
  color: #fff;
  box-shadow: 0 7px 16px -8px rgba(91, 91, 224, 0.9);
}
.step-dot.done i {
  background: var(--ok-soft);
  color: var(--ok);
}
.panel {
  padding: 26px 28px;
}
.panel-head h1 {
  margin: 0 0 6px;
  font-size: 23px;
  letter-spacing: -0.02em;
}
.panel-head > p,
.ph-top p {
  margin: 0;
  font-size: 13px;
  color: var(--muted);
  line-height: 1.7;
}
.ph-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}
.ph-actions {
  display: flex;
  gap: 8px;
  flex: none;
  flex-wrap: wrap;
}
.tip {
  margin-top: 10px !important;
  padding: 9px 12px;
  border-radius: 10px;
  background: #f7f8ff;
}
.course-source {
  margin-top: 8px !important;
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--muted);
}
.course-source a {
  color: var(--primary);
  text-decoration: underline;
}
.link-btn {
  padding: 0;
  color: var(--primary);
  font-size: 12px;
  text-decoration: underline;
}
.btn.sm {
  height: 34px;
  padding: 0 13px;
  font-size: 12.5px;
  flex: none;
}
.form-row {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 20px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
  min-width: 190px;
}
.field.level {
  flex: 0 0 140px;
}
.field > span {
  font-size: 12px;
  font-weight: 700;
  color: var(--muted);
}
.field em {
  font-style: normal;
  font-size: 11.5px;
  color: var(--muted);
}
.inp {
  height: 42px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: var(--card);
  color: var(--fg);
  font-family: inherit;
  font-size: 13.5px;
  min-width: 0;
}
.inp:focus {
  outline: none;
  border-color: var(--primary);
  box-shadow: var(--ring);
}
.inp.big {
  height: 46px;
  font-size: 15px;
}
/* 只给纵向堆叠的字段加间距，避免把同行右侧的「报考层次」也推下去 */
.panel > .field + .field,
.form-row + .field {
  margin-top: 15px;
}
.picked {
  margin-top: 18px;
  padding: 13px 15px;
  border-radius: 13px;
  border: 1px solid color-mix(in srgb, var(--primary) 25%, #fff);
  background: linear-gradient(120deg, var(--primary-soft), #fbfaff);
}
.picked.empty {
  border: 1px dashed var(--line-strong);
  background: #fbfcff;
  color: var(--muted);
  font-size: 13px;
}
.picked-main {
  display: flex;
  align-items: baseline;
  gap: 9px;
  flex-wrap: wrap;
}
.picked-label {
  font-size: 11.5px;
  font-weight: 700;
  color: var(--muted);
}
.picked-main b {
  font-size: 16px;
  letter-spacing: -0.01em;
}
.picked-main code {
  padding: 1px 7px;
  border-radius: 999px;
  background: #fff;
  color: var(--primary);
  font-size: 11.5px;
  font-family: inherit;
  font-weight: 600;
}
.picked em {
  display: block;
  margin-top: 4px;
  font-style: normal;
  font-size: 11.5px;
  color: var(--muted);
}
.catalog {
  margin: 15px 0 4px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #fcfcff;
}
.catalog-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.catalog-title {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
  font-size: 13px;
}
.catalog-badge {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 10.5px;
  font-weight: 700;
  color: var(--primary);
  background: var(--primary-soft);
}
.catalog-badge.national {
  color: var(--warn);
  background: var(--warn-soft);
}
.catalog-title b {
  font-size: 13.5px;
}
.catalog-src {
  font-size: 11.5px;
  color: var(--primary);
  white-space: nowrap;
}
.catalog-src:hover {
  text-decoration: underline;
}
.catalog-note {
  margin: 7px 0 10px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--muted);
}
.manual-major {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed var(--line);
}
.manual-title {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 8px;
  font-size: 12.5px;
  color: var(--muted);
}
.manual-major.disabled .manual-row .inp {
  opacity: .55;
  cursor: not-allowed;
}
.manual-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.manual-row .inp {
  flex: 1;
  min-width: 180px;
}
.manual-row .inp.code {
  flex: 0 1 180px;
}
.catalog-loading,
.catalog-error,
.catalog-empty {
  padding: 18px 0;
  text-align: center;
  font-size: 12.5px;
  color: var(--muted);
}
.catalog-error {
  color: var(--bad);
}
.catalog-search {
  height: 36px;
  font-size: 12.5px;
}
.catalog-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 6px;
  max-height: 240px;
  overflow-y: auto;
  margin-top: 9px;
  padding-right: 3px;
}
.catalog-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 9px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #fff;
  text-align: left;
  font-size: 12.5px;
  transition: 0.14s;
}
.catalog-item:hover {
  border-color: color-mix(in srgb, var(--primary) 45%, #fff);
  background: var(--primary-soft);
}
.catalog-item.on {
  border-color: var(--primary);
  background: var(--primary-soft);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--primary) 30%, transparent);
}
.ci-code {
  flex: none;
  min-width: 64px;
  color: var(--muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.catalog-item.on .ci-code {
  color: var(--primary);
}
.ci-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chip {
  height: 29px;
  padding: 0 11px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: var(--card);
  color: var(--muted);
  font-size: 12px;
  font-weight: 600;
  transition: 0.15s;
}
.chip:hover {
  border-color: var(--primary);
  color: var(--primary);
}
.chip.on {
  border-color: color-mix(in srgb, var(--primary) 32%, #fff);
  background: var(--primary-soft);
  color: var(--primary);
}
.chip.sm {
  height: 26px;
  font-size: 11.5px;
}
.panel-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-top: 26px;
  padding-top: 18px;
  border-top: 1px solid var(--line);
}
.foot-right {
  display: flex;
  align-items: center;
  gap: 14px;
}
.list-tools {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 18px 0 12px;
  flex-wrap: wrap;
}
.list-tools .search {
  flex: 1;
  min-width: 180px;
}
.check-all {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}
.sel-count {
  font-size: 12.5px;
  color: var(--muted);
}
.sel-count b {
  color: var(--primary);
}
.subject-list {
  border: 1px solid var(--line);
  border-radius: 13px;
  overflow: hidden;
  max-height: 430px;
  overflow-y: auto;
}
.subject-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px 11px;
  border-bottom: 1px solid var(--line);
  background: var(--card);
}
.subject-row:last-child {
  border-bottom: none;
}
.subject-row.on {
  background: #fafaff;
}
.subject-row .ck {
  flex: none;
  width: 16px;
  height: 16px;
  accent-color: var(--primary);
}
.subject-row .code {
  flex: 0 0 110px;
  height: 36px;
  font-size: 12.5px;
}
.subject-row .name {
  flex: 1;
  height: 36px;
}
.have {
  flex: none;
  font-size: 11px;
  color: var(--ok);
  background: var(--ok-soft);
  padding: 2px 7px;
  border-radius: 999px;
}
.tag.official {
  flex: none;
  font-size: 10.5px;
  font-weight: 700;
  color: var(--primary);
  background: var(--primary-soft);
  padding: 2px 7px;
  border-radius: 999px;
}
.row-del {
  flex: none;
  width: 27px;
  height: 27px;
  border-radius: 8px;
  color: var(--muted);
}
.row-del:hover {
  background: var(--bad-soft);
  color: var(--bad);
}
.list-empty {
  padding: 28px 0;
  text-align: center;
  font-size: 13px;
  color: var(--muted);
}
.add-row {
  margin-top: 10px;
  height: 36px;
  padding: 0 12px;
  border: 1px dashed var(--line-strong);
  border-radius: 10px;
  color: var(--muted);
  font-size: 12.5px;
}
.add-row:hover {
  border-color: var(--primary);
  color: var(--primary);
}
.panel-kicker {
  display: inline-block;
  margin-bottom: 6px;
  color: var(--primary);
  font-size: 10.5px;
  font-weight: 800;
  letter-spacing: .14em;
  text-transform: uppercase;
}
.date-panel-head {
  margin-bottom: 20px;
}
.date-block {
  margin-top: 16px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 15px;
  background: #fcfcff;
}
.date-block-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.date-block-head b {
  font-size: 13px;
}
.date-block-head span {
  font-size: 12px;
  color: var(--muted);
}
.date-quick {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(132px, 1fr));
  gap: 9px;
}
.date-chip {
  height: 58px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 1px;
  padding: 0 14px;
  border-radius: 12px;
  border: 1px solid var(--line);
  background: var(--card);
  text-align: left;
  transition: .15s;
}
.date-chip:hover {
  border-color: var(--primary);
  background: var(--primary-soft);
}
.date-chip.on {
  border-color: var(--primary);
  background: linear-gradient(135deg, #eef0ff, #f8f6ff);
  box-shadow: inset 0 0 0 1px rgba(91, 91, 224, .12);
}
.dc-month {
  font-size: 11.5px;
  color: var(--muted);
}
.dc-day {
  font-size: 20px;
  line-height: 1.1;
  font-weight: 800;
  color: var(--fg);
  font-variant-numeric: tabular-nums;
}
.date-chip.on .dc-month,
.date-chip.on .dc-day {
  color: var(--primary);
}
.date-row {
  display: flex;
  align-items: stretch;
  gap: 14px;
  flex-wrap: wrap;
}
.date-row .field {
  flex: 1 1 280px;
  margin: 0;
}
.date-row .inp.big {
  height: 50px;
  font-size: 16px;
  font-weight: 600;
}
.countdown {
  display: grid;
  grid-template-columns: auto auto;
  align-content: center;
  column-gap: 7px;
  min-width: 154px;
  padding: 10px 18px;
  border-radius: 14px;
  background: linear-gradient(135deg, #eef0ff, #f8f6ff);
  border: 1px solid #e0e2ff;
}
.countdown span {
  grid-column: 1 / -1;
  margin-bottom: 1px;
  font-size: 11.5px;
  color: var(--muted);
}
.countdown b {
  font-size: 32px;
  line-height: 1;
  color: var(--primary);
  font-variant-numeric: tabular-nums;
}
.countdown em {
  align-self: end;
  margin-bottom: 5px;
  color: var(--muted);
  font-size: 12px;
  font-style: normal;
}
.summary {
  margin-top: 16px;
  padding: 18px 20px;
  border-radius: 15px;
  border: 1px solid #e0e2ff;
  background:
    radial-gradient(260px 120px at 100% 0%, rgba(109, 94, 252, .08), transparent 70%),
    linear-gradient(135deg, #fcfcff, #f8f7ff);
}
.sum-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.sum-title {
  font-size: 12px;
  font-weight: 800;
  letter-spacing: .04em;
  color: var(--muted);
}
.sum-count {
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--primary-soft);
  color: var(--primary);
  font-size: 11.5px;
  font-weight: 700;
}
.sum-date {
  margin: 6px 0 12px;
  font-size: 19px;
  font-weight: 800;
  letter-spacing: -.01em;
}
.sum-subjects {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.sum-subject {
  display: inline-flex;
  align-items: center;
  height: 34px;
  padding: 0 12px;
  border: 1px solid color-mix(in srgb, var(--primary) 18%, transparent);
  border-radius: 10px;
  background: var(--primary-soft);
  color: var(--primary-dark);
  font-size: 12.5px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
}
.per-subject {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--card);
  font-size: 12.5px;
  color: var(--fg);
  cursor: pointer;
  transition: .15s;
}
.per-subject:hover {
  border-color: color-mix(in srgb, var(--primary) 35%, var(--line));
  background: #fafaff;
}
.per-subject input {
  width: 16px;
  height: 16px;
  accent-color: var(--primary);
}
.per-subject span {
  color: var(--muted);
}
.per-list {
  margin-top: 10px;
  border: 1px solid var(--line);
  border-radius: 12px;
  overflow: hidden;
}
.per-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 14px;
  border-bottom: 1px solid var(--line);
  background: var(--card);
}
.per-row:nth-child(even) {
  background: #fcfcff;
}
.per-row:last-child {
  border-bottom: none;
}
.per-row span {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
}
.per-row .inp {
  flex: 0 0 178px;
  height: 38px;
}
.date-foot {
  margin-top: 22px;
}
.empty {
  text-align: center;
  color: var(--muted);
}
@media (max-width: 720px) {
  .setup-page {
    padding: 16px 12px 40px;
  }
  .setup-head {
    margin-bottom: 14px;
  }
  .brand span {
    display: none;
  }
  .steps {
    gap: 12px;
  }
  .step-dot {
    font-size: 11.5px;
  }
  .panel {
    padding: 20px 16px;
  }
  .panel-head h1 {
    font-size: 20px;
  }
  .ph-top {
    flex-direction: column;
    gap: 10px;
  }
  .panel-foot {
    flex-wrap: wrap;
  }
  .foot-right {
    width: 100%;
    justify-content: space-between;
  }
  .subject-row {
    flex-wrap: wrap;
  }
  .subject-row .code {
    flex: 0 0 86px;
  }
  .subject-row .name {
    flex: 1 1 140px;
  }
  .date-block {
    padding: 14px;
  }
  .date-row .field,
  .per-row .inp {
    flex-basis: 100%;
  }
  .countdown {
    width: 100%;
    min-width: 0;
  }
  .catalog-list {
    grid-template-columns: 1fr;
  }
}
</style>
