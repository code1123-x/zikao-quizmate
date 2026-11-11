<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, TYPE_NAME, type PendingSource, type QuestionView } from '../api'
import { toast } from '../toast'
import { confirmDialog } from '../dialog'

const route = useRoute()
const router = useRouter()

const subjectId = Number(route.params.subjectId)
const subjectName = ref('')
const sources = ref<PendingSource[]>([])
const activeSource = ref<PendingSource | null>(null)

const mode = ref<'one' | 'batch'>('one')
const questions = ref<QuestionView[]>([])
const idx = ref(0)
const chosen = ref<string[]>([])
const busy = ref(false)
const saved = ref(0)
const skippedCount = ref(0)
const done = ref(false)

const batchText = ref('')
const batchResult = ref('')
const fromOne = ref(false)

const answerImages = ref<string[]>([])
const showImages = ref(false)
const zoomSrc = ref<string | null>(null)
const batchInput = ref<HTMLTextAreaElement | null>(null)

const ocrMap = ref<Record<number, string>>({})
const ocrState = ref<'idle' | 'loading' | 'done'>('idle')
const ocrMsg = ref('')
const ocrSkipped = ref(false)
const ocrLoose = ref(false)
const compare = ref<{ picked: string; expected: string; source: 'OCR' | 'AI'; reason?: string } | null>(null)

const aiHasKey = ref(false)
const aiLoaded = ref(false)
const aiEnabled = ref(localStorage.getItem('fillAiEnabled') !== '0')
const aiChecking = ref(false)
const aiVerifying = ref(false)

const expected = computed(() => {
  const q = current.value
  return q?.qno ? (ocrMap.value[q.qno] ?? '') : ''
})

const current = computed(() => questions.value[idx.value])
const total = computed(() => questions.value.length)
const options = computed(() => {
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

const totalPending = computed(() => sources.value.reduce((a, s) => a + s.count, 0))

const batchTokens = computed(() =>
  batchText.value.trim() ? batchText.value.trim().split(/[\s,，、;；|]+/).filter(Boolean) : []
)

/** 带题号的写法：22A / 22.A / 22、A / 22 A */
const NUMBERED = /^(\d{1,3})\s*[.、．:：\-]?\s*(\S{1,6})$/

/** AI 不可用时的典型错误（Key 过期、余额不足、鉴权失败等） */
const CONN_ERROR = /401|403|未配置|API Key|鉴权|认证|余额|额度|quota|unauthorized|token|过期/i

const numberedMap = computed(() => {
  const map = new Map<number, string>()
  for (const t of batchTokens.value) {
    const m = NUMBERED.exec(t)
    if (m) map.set(Number(m[1]), m[2].toUpperCase())
  }
  return map
})

const isNumbered = computed(() => numberedMap.value.size > 0)

const plainList = computed(() =>
  batchTokens.value.filter((t) => !NUMBERED.test(t)).map((t) => t.toUpperCase())
)

const mixedInput = computed(() => isNumbered.value && plainList.value.length > 0)

function previewFor(q: QuestionView, i: number) {
  if (isNumbered.value) return numberedMap.value.get(q.qno ?? -1) ?? ''
  if (fromOne.value) return q.qno ? (plainList.value[q.qno - 1] ?? '') : ''
  return plainList.value[i] ?? ''
}

onMounted(async () => {
  try {
    const [subs, list] = await Promise.all([api.subjects(), api.pendingSources(subjectId)])
    subjectName.value = subs.find((s) => s.id === subjectId)?.name ?? ''
    sources.value = list
    if (list.length) await selectSource(list[0])
    else done.value = true
  } catch (e) {
    toast((e as Error).message)
    router.replace('/')
  }
  window.addEventListener('keydown', onKey)
  loadAiConfig()
})

async function loadAiConfig(retry = 2) {
  try {
    const c = await api.aiConfig()
    aiHasKey.value = c.hasKey
    aiLoaded.value = true
  } catch {
    // 服务刚启动时可能偶发失败，重试两次
    if (retry > 0) {
      window.setTimeout(() => loadAiConfig(retry - 1), 800)
    } else {
      aiHasKey.value = false
      aiLoaded.value = true
    }
  }
}

watch(aiEnabled, (v) => localStorage.setItem('fillAiEnabled', v ? '1' : '0'))

async function onAiToggle(e: Event) {
  const input = e.target as HTMLInputElement
  const want = input.checked

  // 取消勾选：直接关掉
  if (!want) {
    aiEnabled.value = false
    return
  }

  // 勾选前先确认配置存在
  await loadAiConfig(0)
  if (!aiHasKey.value) {
    const go = await confirmDialog({
      title: 'AI 还没配置',
      message: '勾选 AI 校验前，需要先填好接口地址、模型和 API Key。',
      detail: '现在去 AI 设置页配置吗？配置好回来再勾选。',
      confirmText: '去配置',
      cancelText: '先不用'
    })
    if (go) router.push('/ai')
    input.checked = false
    aiEnabled.value = false
    return
  }

  // 再实际测一次连接（Key 过期 / 余额不足 / 网络不通都会在这里暴露）
  aiVerifying.value = true
  let probe: Awaited<ReturnType<typeof api.testAi>> | null = null
  try {
    probe = await api.testAi()
  } catch {
    probe = null
  }
  aiVerifying.value = false

  if (!probe || !probe.ok) {
    input.checked = false
    aiEnabled.value = false
    toast(`AI 连接不可用：${probe?.message ?? '网络错误'}，正在打开设置…`)
    router.push('/ai')
    return
  }

  aiEnabled.value = true
}

onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

async function selectSource(s: PendingSource) {
  activeSource.value = s
  mode.value = 'one'
  batchText.value = ''
  batchResult.value = ''
  compare.value = null
  ocrMap.value = {}
  ocrState.value = 'idle'
  ocrMsg.value = ''
  ocrSkipped.value = false
  skippedCount.value = 0
  await loadImages(s.source)
  await reloadQuestions()
  loadAiConfig(0)
  if (answerImages.value.length) {
    ensureOcr() // 后台识别，不挡页面
  }
}

function setMode(m: 'one' | 'batch', autoFill = true) {
  mode.value = m
  if (m === 'batch') {
    nextTick(() => batchInput.value?.focus())
    if (autoFill && !batchText.value.trim()) fillFromOcr()
  }
}

/** 识别答案图；一次识别会缓存，之后很快 */
async function ensureOcr(force = false): Promise<Record<number, string>> {
  if (!force && ocrState.value === 'done') return ocrMap.value
  if (!force && ocrState.value === 'loading') return ocrMap.value
  const source = activeSource.value?.source
  if (!source) return {}
  ocrState.value = 'loading'
  try {
    const r = await api.ocrAnswers(source, subjectId, ocrLoose.value)
    const map: Record<number, string> = {}
    for (const a of r.answers) map[a.qno] = a.answer
    ocrMap.value = map
    ocrMsg.value = r.message
    return map
  } catch (e) {
    ocrMsg.value = (e as Error).message
    return {}
  } finally {
    ocrState.value = 'done'
  }
}

/** 把识别结果填进输入框（带题号，最稳） */
async function fillFromOcr(force = false) {
  const map = await ensureOcr(force)
  const keys = Object.keys(map)
    .map(Number)
    .sort((a, b) => a - b)
  if (!keys.length) {
    toast(ocrMsg.value || '没有识别出答案')
    return
  }
  if (batchText.value.trim() && !force) return
  batchText.value = keys.map((k) => `${k}${map[k]}`).join(' ')
  toast(`已自动识别 ${keys.length} 题，核对后点「填入」`)
}

async function loadImages(source: string) {
  answerImages.value = []
  showImages.value = false
  try {
    answerImages.value = await api.answerImages(source)
  } catch {
    answerImages.value = []
  }
}

async function reloadQuestions() {
  if (!activeSource.value) return
  busy.value = true
  try {
    const list = await api.pendingQuestions(subjectId, activeSource.value.source, 300)
    questions.value = list
    idx.value = 0
    chosen.value = []
    saved.value = 0
    skippedCount.value = 0
    done.value = list.length === 0
  } catch (e) {
    toast((e as Error).message)
  } finally {
    busy.value = false
  }
}

async function refreshSources() {
  sources.value = await api.pendingSources(subjectId)
  const cur = sources.value.find((s) => s.source === activeSource.value?.source)
  if (cur) {
    activeSource.value = cur
  } else if (sources.value.length) {
    await selectSource(sources.value[0])
  } else {
    activeSource.value = null
    done.value = true
  }
}

function pick(key: string) {
  if (busy.value || done.value || compare.value) return
  if (ocrState.value === 'loading' && !ocrSkipped.value) return
  const q = current.value!
  if (q.type === 'MULTIPLE') {
    const i = chosen.value.indexOf(key)
    if (i >= 0) chosen.value.splice(i, 1)
    else chosen.value.push(key)
    return
  }
  save(key)
}

async function saveMulti() {
  if (!chosen.value.length) {
    toast('至少选一项')
    return
  }
  await save([...chosen.value].sort().join(''))
}

async function save(answer: string) {
  const q = current.value
  if (!q) return
  const expected = q.qno ? ocrMap.value[q.qno] : undefined
  if (expected && expected !== answer) {
    // 和答案图识别结果不一致：先不落库，让用户看一眼
    compare.value = { picked: answer, expected, source: 'OCR' }
    return
  }
  if (expected) {
    await doSave(answer, '✅ 与答案图一致')
    return
  }
  // 没有答案图可用时，让 AI 独立做一遍来判断
  if (aiEnabled.value && aiHasKey.value && !busy.value) {
    aiChecking.value = true
    busy.value = true
    let res: Awaited<ReturnType<typeof api.aiCheckOne>> | null = null
    try {
      res = await api.aiCheckOne(q.id, answer)
    } catch {
      res = null
    }
    busy.value = false
    aiChecking.value = false
    if (res?.ok) {
      if (res.correct) {
        await doSave(answer, '✅ AI 也认为正确')
      } else if (res.aiAnswer) {
        compare.value = {
          picked: answer,
          expected: res.aiAnswer,
          source: 'AI',
          reason: res.reason
        }
      } else {
        await doSave(answer, '')
      }
      return
    }
    // Key 过期 / 余额不足 / 网络不通：停用并跳去设置
    if (res && CONN_ERROR.test(res.message ?? '')) {
      aiEnabled.value = false
      toast(`AI 连接不可用：${res.message}，正在打开设置…`)
      router.push('/ai')
      return
    }
  }
  await doSave(answer, '')
}

async function doSave(answer: string, msg: string) {
  const q = current.value
  if (!q) return
  compare.value = null
  busy.value = true
  try {
    await api.setAnswer(q.id, answer)
    saved.value++
    if (msg) toast(msg)
    next()
  } catch (e) {
    toast((e as Error).message)
  } finally {
    busy.value = false
  }
}

async function saveExpected() {
  if (!compare.value) return
  await doSave(compare.value.expected,
    compare.value.source === 'AI' ? '已按 AI 答案填入' : '已按答案图填入')
}

async function savePicked() {
  if (!compare.value) return
  await doSave(compare.value.picked, '')
}

function skip() {
  if (busy.value) return
  compare.value = null
  skippedCount.value++
  next()
}

function resumeSource() {
  if (activeSource.value) selectSource(activeSource.value)
}

function next() {
  chosen.value = []
  compare.value = null
  if (idx.value >= total.value - 1) {
    done.value = true
    api.pendingSources(subjectId).then((list) => {
      sources.value = list
      const cur = list.find((s) => s.source === activeSource.value?.source)
      if (cur) activeSource.value = cur
    })
  } else {
    idx.value++
  }
}

async function submitBatch() {
  if (!activeSource.value) return
  if (!batchTokens.value.length) {
    toast('先把答案贴进来')
    return
  }
  busy.value = true
  try {
    const r = await api.batchAnswer(subjectId, activeSource.value.source, batchText.value, fromOne.value)
    batchResult.value = r.message
    toast(r.message)
    batchText.value = ''
    const keepBatch = mode.value === 'batch'
    await refreshSources()
    if (activeSource.value) {
      ocrMap.value = {}
      ocrState.value = 'idle'
      ocrMsg.value = ''
      await loadImages(activeSource.value.source)
      await reloadQuestions()
      if (keepBatch) setMode('batch', false)
    }
  } catch (e) {
    toast((e as Error).message)
  } finally {
    busy.value = false
  }
}

function onKey(e: KeyboardEvent) {
  if (zoomSrc.value) {
    if (e.key === 'Escape') zoomSrc.value = null
    return
  }
  if (compare.value) {
    if (e.key === 'Escape') skip()
    return
  }
  if (done.value || mode.value !== 'one') return
  if (e.key >= '1' && e.key <= '9') {
    const opt = options.value[Number(e.key) - 1]
    if (opt) pick(opt.key)
  } else if (e.key === 'Enter' && current.value?.type === 'MULTIPLE') {
    saveMulti()
  } else if (e.key === 'Escape') {
    router.push('/')
  }
}

function shortSource(s: string) {
  return s.replace(/^\[?\d{5}\]?\s*/, '').replace(/自考|真题|及答案|答案解析|答案加解析|答案/g, '').trim()
}
</script>

<template>
  <div class="practice">
    <div class="p-head">
      <button class="back" @click="router.push('/')">← 退出</button>
      <div class="p-title">{{ subjectName }} · 补答案</div>
      <div class="timer">待补 {{ totalPending }}</div>
    </div>

    <div class="learn-body" :style="{ maxWidth: mode === 'batch' ? '1180px' : '860px' }">
      <div v-if="!sources.length" class="learn-done">
        <div class="big">🎉</div>
        <h3>所有选择题都有答案了</h3>
        <p>可以直接去刷题了</p>
        <button class="btn" @click="router.push('/')">返回首页</button>
      </div>

      <template v-else>
        <div class="paper-list">
          <button
            v-for="s in sources"
            :key="s.source"
            class="paper"
            :class="{ on: activeSource?.source === s.source }"
            @click="selectSource(s)"
          >
            <span class="py">{{ s.year || '—' }}</span>
            <span class="pn">{{ shortSource(s.source) || s.source }}</span>
            <span class="pc">{{ s.count }} 题</span>
          </button>
        </div>

        <div class="modes">
          <button class="tab" :class="{ on: mode === 'one' }" @click="setMode('one')">逐题补</button>
          <button class="tab" :class="{ on: mode === 'batch' }" @click="setMode('batch')">批量粘贴</button>
          <button
            v-if="mode === 'one' && answerImages.length"
            class="tab"
            :class="{ on: showImages }"
            @click="showImages = !showImages"
          >
            答案图 {{ answerImages.length }}
          </button>
          <span v-if="mode === 'batch'" class="muted" style="font-size: 12px; align-self: center">
            点图片可放大，Esc 关闭
          </span>
          <label
            class="fromone"
            style="margin: 0 0 0 auto"
            :title="aiHasKey ? '用 DeepSeek 判断选择的答案对不对' : '还没有配置 AI 接口'"
          >
            <input :checked="aiEnabled && aiHasKey" type="checkbox" @change="onAiToggle" />
            AI 校验{{ aiLoaded && !aiHasKey ? '（未配置）' : '' }}
          </label>
        </div>

        <!-- 逐题 -->
        <template v-if="mode === 'one'">
          <div v-if="showImages && answerImages.length" class="imgwrap">
            <img
              v-for="(u, i) in answerImages"
              :key="u"
              :src="u"
              :alt="'答案图 ' + (i + 1)"
              loading="lazy"
              @click="zoomSrc = u"
            />
          </div>

          <div v-if="ocrState === 'loading' && !ocrSkipped" class="ocrwait">
            <span>正在识别答案图（{{ answerImages.length }} 张），识别完选中答案会立刻告诉你对不对…</span>
            <button class="mini" @click="ocrSkipped = true">先不等，直接填</button>
          </div>
          <div v-else-if="ocrState === 'done' && !Object.keys(ocrMap).length" class="ocrwait none">
            <span v-if="aiHasKey && aiEnabled">
              这套卷没有可用的答案图，选完答案会用 AI 判断对错（AI 也可能出错）
            </span>
            <span v-else-if="answerImages.length">
              这套卷没识别出答案（图片可能不是答案页），选择答案无法自动判断对错
            </span>
            <span v-else>这套卷没有答案图，选择答案无法自动判断对错</span>
          </div>

          <template v-if="!done && current">
            <div class="qmeta">
              <span class="pill">{{ TYPE_NAME[current.type] ?? current.type }}</span>
              <span v-if="current.qno" class="pill">第 {{ current.qno }} 题</span>
              <span v-if="current.year">{{ current.year }} 年</span>
              <span class="muted">第 {{ idx + 1 }} / {{ total }} 题</span>
            </div>

            <div class="qbody" style="font-size: 16px">{{ current.stem }}</div>

            <div v-if="compare" class="cmp">
              <div class="cmp-t">
                你选 <b>{{ compare.picked }}</b>，
                {{ compare.source === 'AI' ? 'AI 认为' : '答案图识别为' }}
                <b>{{ compare.expected }}</b>
              </div>
              <div class="cmp-s">
                {{
                  compare.source === 'AI'
                    ? compare.reason || 'AI 的判断也可能出错，请自己再确认一下'
                    : '识别可能有误，请对照答案图确认后再保存'
                }}
              </div>
              <div class="cmp-b">
                <button class="btn" :disabled="busy" @click="saveExpected">
                  {{ compare.source === 'AI' ? '按 AI 填' : '按答案图填' }} {{ compare.expected }}
                </button>
                <button class="mini" :disabled="busy" @click="savePicked">仍然填 {{ compare.picked }}</button>
                <button class="mini" :disabled="busy" @click="skip">跳过</button>
              </div>
            </div>

            <div class="opts">
              <button
                v-for="opt in options"
                :key="opt.key"
                class="opt"
                :class="{ sel: chosen.includes(opt.key) }"
                :disabled="busy || !!compare || (ocrState === 'loading' && !ocrSkipped)"
                @click="pick(opt.key)"
              >
                <span class="k">{{ opt.key }}</span>
                <span class="t">{{ opt.text }}</span>
              </button>
            </div>

            <div v-if="current.type === 'MULTIPLE'" style="margin-top: 16px; display: flex; gap: 12px; align-items: center">
              <span class="muted" style="font-size: 13px">已选 {{ chosen.length }} 项</span>
              <button class="btn" :disabled="!chosen.length || busy || !!compare" @click="saveMulti">保存（Enter）</button>
            </div>

            <div style="margin-top: 24px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap">
              <button class="mini" @click="skip">跳过这题</button>
              <span class="muted" style="font-size: 13px">本次已补 {{ saved }} 题</span>
              <span v-if="aiVerifying" class="ai-thinking">· 正在检查 AI 连接…</span>
              <span v-else-if="aiChecking" class="ai-thinking">· AI 正在判断…</span>
              <span v-else-if="ocrState === 'loading'" class="muted" style="font-size: 12px">· 正在识别答案图…</span>
              <span v-else-if="expected" class="muted" style="font-size: 12px">
                · 答案图已识别 {{ Object.keys(ocrMap).length }} 题，选完会告诉你对不对
              </span>
              <span v-else-if="aiHasKey && aiEnabled" class="muted" style="font-size: 12px">
                · 这题没有答案图，选完会用 AI 判断
              </span>
              <span v-else-if="Object.keys(ocrMap).length" class="muted" style="font-size: 12px">
                · 这题没识别到答案，无法校验
              </span>
            </div>
          </template>

          <div v-else class="learn-done">
            <div class="big">{{ skippedCount > 0 ? '⏭' : saved > 0 ? '✅' : '🎉' }}</div>
            <h3 v-if="skippedCount > 0">还有 {{ skippedCount }} 题被跳过</h3>
            <h3 v-else>{{ saved > 0 ? '这套卷补完了' : '这套卷没有待补的题了' }}</h3>
            <p v-if="skippedCount > 0">跳过的题还在待补列表里，可以重新选这套卷继续补。</p>
            <p v-else-if="saved > 0">共补了 {{ saved }} 题，已经可以刷了</p>
            <div style="display: flex; gap: 10px; justify-content: center; flex-wrap: wrap">
              <button v-if="skippedCount > 0" class="btn" @click="resumeSource">继续补这套</button>
              <button v-else-if="sources.length > 1" class="btn" @click="selectSource(sources.find((s) => s.source !== activeSource?.source) ?? sources[0])">补下一套</button>
              <button class="btn ghost" @click="router.push('/')">返回首页</button>
            </div>
          </div>
        </template>

        <!-- 批量 -->
        <template v-else>
          <div class="batch-grid">
            <div class="batch-view">
              <div v-if="answerImages.length" class="imgwrap tight">
                <img
                  v-for="(u, i) in answerImages"
                  :key="u"
                  :src="u"
                  :alt="'答案图 ' + (i + 1)"
                  loading="lazy"
                  @click="zoomSrc = u"
                />
              </div>
              <div v-else class="imgnone">
                这套卷的原始文件里没有可用的答案图。<br />
                答案可能缺失，或者只存在于别的资料里。<br />
                可以先跳过这套，或者用「逐题补」凭印象填。
              </div>
            </div>

            <div class="batch-side">
              <div class="batch-tip">
                对照答案图，三种写法都支持：<br />
                ① 带题号（最稳，直接照答案图抄全卷）：<code>22A 23B 24C</code><br />
                ② 整卷顺序（勾上下面的框）：<code>B C A D …</code> 第 1 个就是第 1 题<br />
                ③ 待补顺序（不勾）：<code>A B C AB D</code> 按右边列表的先后
              </div>

              <ol class="pending-list">
                <li v-for="(q, i) in questions" :key="q.id" :class="{ filled: previewFor(q, i) }">
                  <span class="no">{{ q.qno ?? i + 1 }}</span>
                  <span class="tp">{{ TYPE_NAME[q.type] ?? q.type }}</span>
                  <span class="st">{{ q.stem.replace(/\s+/g, ' ').slice(0, 60) }}</span>
                  <span v-if="previewFor(q, i)" class="pv">{{ previewFor(q, i) }}</span>
                </li>
              </ol>

              <label class="fromone">
                <input v-model="fromOne" type="checkbox" />
                按整卷题号排（第 1 个答案 = 第 1 题）
              </label>
              <div v-if="fromOne" class="warn" style="margin: -2px 0 8px">
                如果这套卷的题号不是从 1 开始（比如第二大题从 21 开始），请改用带题号写法。
              </div>

              <div class="ocrbar">
                <button class="mini" :disabled="ocrState === 'loading'" @click="fillFromOcr(true)">
                  {{ ocrState === 'loading' ? '识别中…' : '自动识别答案' }}
                </button>
                <label class="fromone" style="margin: 0">
                  <input v-model="ocrLoose" type="checkbox" @change="ocrState = 'idle'" />
                  含低置信度（可能有个别错）
                </label>
                <span class="muted">
                  {{ ocrMsg || '识别结果会填进输入框，请对照答案图核对后再填入' }}
                </span>
              </div>

              <textarea
                ref="batchInput"
                v-model="batchText"
                class="batch-input"
                rows="3"
                placeholder="例如：22A 23B 24C 25ABCD 或 A B C AB D"
              ></textarea>

              <div style="display: flex; gap: 12px; align-items: center; margin-top: 12px; flex-wrap: wrap">
                <button class="btn" :disabled="busy || !batchTokens.length || mixedInput" @click="submitBatch">
                  填入（{{ batchTokens.length }} 个答案）
                </button>
                <span class="muted" style="font-size: 13px">共 {{ questions.length }} 题待补</span>
                <span v-if="mixedInput" class="warn">带题号和纯顺序不要混着写</span>
                <span v-if="batchResult" class="muted" style="font-size: 13px">{{ batchResult }}</span>
              </div>
            </div>
          </div>
        </template>
      </template>
    </div>

    <div v-if="zoomSrc" class="zoom" @click.self="zoomSrc = null">
      <img :src="zoomSrc" alt="答案图" />
      <button class="zoom-close" @click="zoomSrc = null">×</button>
    </div>
  </div>
</template>

<style scoped>
.paper-list {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 8px;
  margin-bottom: 14px;
}
.paper {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--card);
  text-align: left;
  max-width: 220px;
}
.paper.on {
  border-color: var(--primary);
  background: var(--primary-soft);
}
.py {
  font-size: 11px;
  color: var(--muted);
}
.pn {
  font-size: 12.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 190px;
}
.pc {
  font-size: 11px;
  color: var(--primary);
}
.modes {
  display: flex;
  gap: 6px;
  margin-bottom: 16px;
}
.tab {
  padding: 6px 14px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: var(--card);
  font-size: 13px;
}
.tab.on {
  border-color: var(--primary);
  background: var(--primary);
  color: #fff;
}
.batch-tip {
  font-size: 13px;
  color: var(--muted);
  line-height: 1.8;
  margin-bottom: 12px;
}
.warn {
  font-size: 13px;
  color: #d33;
}
.fromone {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--muted);
  margin-bottom: 8px;
  cursor: pointer;
}
.fromone input {
  accent-color: var(--primary);
}
.ocrbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 12.5px;
  flex-wrap: wrap;
}
.cmp {
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid #f0c36d;
  background: #fff8e6;
  border-radius: 10px;
}
.cmp-t {
  font-size: 14px;
}
.cmp-t b {
  color: #a35c00;
}
.cmp-s {
  font-size: 12px;
  color: #a08a5b;
  margin: 4px 0 10px;
}
.cmp-b {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.ocrwait {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  padding: 8px 12px;
  margin-bottom: 12px;
  border: 1px solid var(--line);
  background: var(--card);
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--muted);
}
.ocrwait.none {
  border-color: #f0c36d;
  background: #fff8e6;
  color: #a08a5b;
}
.ai-thinking {
  color: var(--primary);
  font-size: 12px;
  animation: ai-pulse 1.2s ease-in-out infinite;
}
@keyframes ai-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: .45; }
}
.imgwrap {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 62vh;
  overflow-y: auto;
  padding: 8px;
  margin-bottom: 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #f6f7f9;
}
.imgwrap.tight {
  margin-bottom: 0;
  max-height: calc(100vh - 190px);
}
.imgwrap img {
  width: 100%;
  height: auto;
  border-radius: 6px;
  background: #fff;
  cursor: zoom-in;
}
.batch-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.batch-view {
  position: sticky;
  top: 12px;
}
.imgnone {
  padding: 18px;
  border: 1px dashed var(--line);
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--muted);
}
@media (max-width: 900px) {
  .batch-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .batch-view {
    position: static;
  }
  .imgwrap.tight {
    max-height: 46vh;
  }
}
.zoom {
  position: fixed;
  inset: 0;
  z-index: 50;
  background: rgba(0, 0, 0, 0.82);
  overflow: auto;
  padding: 20px;
  display: flex;
  justify-content: center;
  align-items: flex-start;
}
.zoom img {
  max-width: none;
  width: min(1100px, 180%);
  background: #fff;
  border-radius: 4px;
}
.zoom-close {
  position: fixed;
  top: 12px;
  right: 16px;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  border: none;
  background: rgba(255, 255, 255, 0.9);
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
}
.batch-tip code {
  background: var(--primary-soft);
  color: var(--primary);
  padding: 2px 6px;
  border-radius: 5px;
}
.pending-list {
  list-style: none;
  padding: 0;
  margin: 0 0 12px;
  max-height: 300px;
  overflow-y: auto;
  border: 1px solid var(--line);
  border-radius: 10px;
}
.pending-list li {
  display: flex;
  gap: 8px;
  align-items: baseline;
  padding: 7px 12px;
  font-size: 12.5px;
  border-bottom: 1px solid var(--line);
}
.pending-list li:last-child {
  border-bottom: none;
}
.pending-list .no {
  flex: 0 0 22px;
  color: var(--muted);
  text-align: right;
}
.pending-list .tp {
  flex: 0 0 auto;
  font-size: 11px;
  color: var(--primary);
}
.pending-list .st {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pending-list li.filled {
  background: #f2fbf4;
}
.pending-list .pv {
  flex: 0 0 auto;
  margin-left: auto;
  padding: 0 7px;
  border-radius: 5px;
  background: #e5f6e9;
  color: #1a7f37;
  font-size: 11.5px;
  font-weight: 600;
}
.batch-input {
  width: 100%;
  box-sizing: border-box;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  font-size: 15px;
  letter-spacing: 2px;
  font-family: inherit;
  resize: vertical;
}
.batch-input:focus {
  outline: none;
  border-color: var(--primary);
}
</style>
