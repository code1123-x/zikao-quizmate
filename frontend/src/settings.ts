import { ref, watch } from 'vue'

export interface PracticeSettings {
  /** 点「练选择题」时一次抽多少道 */
  count: number
  /** 首页今日目标题数 */
  goal: number
}

const KEY = 'quizmate.practiceSettings'
const DEFAULTS: PracticeSettings = { count: 20, goal: 20 }

function clamp(n: unknown, fallback: number): number {
  const v = typeof n === 'number' ? n : Number(n)
  if (!Number.isFinite(v)) return fallback
  return Math.min(200, Math.max(1, Math.round(v)))
}

function load(): PracticeSettings {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return { ...DEFAULTS }
    const parsed = JSON.parse(raw) as Partial<PracticeSettings>
    return {
      count: clamp(parsed.count, DEFAULTS.count),
      goal: clamp(parsed.goal, DEFAULTS.goal)
    }
  } catch {
    return { ...DEFAULTS }
  }
}

/** 当前浏览器的练习偏好，改完即时生效 */
export const practiceSettings = ref<PracticeSettings>(load())

watch(
  practiceSettings,
  (v) => {
    try {
      localStorage.setItem(KEY, JSON.stringify(v))
    } catch {
      /* 隐私模式下写不了就只在本次生效 */
    }
  },
  { deep: true }
)

export function savePracticeSettings(next: PracticeSettings) {
  practiceSettings.value = {
    count: clamp(next.count, DEFAULTS.count),
    goal: clamp(next.goal, DEFAULTS.goal)
  }
}
