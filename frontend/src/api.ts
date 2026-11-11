export interface SubjectView {
  id: number
  code?: string
  name: string
  color: string
  examDate?: string
  questionCount: number
  choiceCount: number
  cardCount: number
  pendingCount: number
  answeredCount: number
  accuracy: number
  dueCount: number
}

export interface MajorView {
  id: number
  code?: string
  name: string
  color: string
  province?: string
  level?: string
  subjectCount: number
  questionCount: number
  choiceCount: number
}

export interface SetupStatus {
  setupDone: boolean
  majorId?: number
  majorName?: string
  province?: string
  level?: string
}

export interface SetupSubjectItem {
  id?: number
  name: string
  code?: string
  examDate?: string
  selected?: boolean
}

export interface SetupCompleteRequest {
  majorId?: number
  majorName?: string
  majorCode?: string
  province?: string
  level?: string
  subjects: SetupSubjectItem[]
}

export interface SetupCompleteResult {
  majorId: number
  created: number
  updated: number
  cleared: number
}

export interface ExamPlanGroup {
  examDate: string
  daysLeft: number
  subjects: SubjectView[]
}

export interface ExamPlan {
  nextExamDate?: string
  daysLeft?: number
  plannedCount: number
  totalSubjectCount: number
  groups: ExamPlanGroup[]
  unplanned: SubjectView[]
}

export interface MajorCatalogItem {
  code: string
  name: string
}

export interface MajorCatalogView {
  province: string
  level: string
  scope: 'province' | 'national'
  sourceUrl?: string
  version?: string
  note?: string
  updatedAt?: string
  count: number
  items: MajorCatalogItem[]
}

export interface MajorCatalogCoverage {
  province: string
  count: number
  version?: string
  updatedAt?: string
  levels: string[]
}

export interface MajorCourseItem {
  code: string
  name: string
}

export interface MajorCourseView {
  province: string
  level: string
  majorCode: string
  majorName: string
  scope: 'province' | 'missing'
  sourceUrl?: string
  version?: string
  note?: string
  updatedAt?: string
  count: number
  courses: MajorCourseItem[]
}

export interface Overview {
  subjectCount: number
  questionCount: number
  choiceCount: number
  cardCount: number
  pendingCount: number
  wrongCount: number
  answeredQuestionCount: number
  todayAnswered: number
  accuracy: number
  dueCount: number
  dueCardCount: number
  streakDays: number
}

export interface CardView {
  id: number
  subjectId: number
  type: string
  title: string
  answer: string
  analysis?: string
  source?: string
  year?: number
  mastery: number
  lastResult?: string
}

export interface CardSummary {
  subjectId: number
  name: string
  color: string
  total: number
  due: number
  mastered: number
}

export interface Option {
  key: string
  text: string
}

export interface QuestionView {
  id: number
  type: string
  stem: string
  options: Option[]
  answer: string
  analysis?: string
  score: number
  source?: string
  year?: number
  tags?: string[]
  chapterId?: number
  qno?: number
  status?: string
}

export interface QuestionPage {
  items: QuestionView[]
  total: number
  page: number
  size: number
}

export interface PendingSource {
  source: string
  year?: number
  count: number
}

export interface StatsOverview {
  answered: number
  correct: number
  accuracy: number
  minutes: number
  activeDays: number
  streak: number
  todayAnswered: number
  todayCorrect: number
  weekAnswered: number
  choiceCount: number
  answeredQuestions: number
  cardCount: number
  pendingCount: number
  wrongCount: number
}

export interface StatsDaily {
  date: string
  answered: number
  correct: number
}

export interface StatsSubject {
  subjectId: number
  name: string
  color: string
  choiceCount: number
  answered: number
  correct: number
  accuracy: number
  pending: number
}

export interface StatsType {
  type: string
  answered: number
  correct: number
  accuracy: number
}

export interface StatsReport {
  overview: StatsOverview
  daily: StatsDaily[]
  subjects: StatsSubject[]
  types: StatsType[]
}

export interface BuildType {
  type: string
  count: number
}

export interface BuildYear {
  year: number
  count: number
}

export interface BuildChapter {
  id: number
  name: string
  count: number
  wrongCount: number
  newCount: number
}

export interface BuildOptions {
  subjectId: number
  choiceCount: number
  types: BuildType[]
  years: BuildYear[]
  chapters: BuildChapter[]
  wrongCount: number
  newCount: number
}

export interface FsEntry {
  name: string
  path: string
  dir: boolean
  size: number
  ext: string
  supported: boolean
}

export interface FsListing {
  root: string
  path: string
  parent: string | null
  entries: FsEntry[]
}

export interface ScanFile {
  name: string
  path: string
  parsed: number
  objective: number
  withAnswer: number
  pending: number
  skipped: number
  duplicates: number
  scanned: boolean
  message: string
  samples: string[]
}

export interface ScanResult {
  files: number
  parsed: number
  withAnswer: number
  pending: number
  scannedFiles: number
  skipped: number
  details: ScanFile[]
}

export interface ImportFileResult {
  file: string
  status: string
  parsed: number
  imported: number
  skipped: number
  withoutAnswer: number
  pending: number
  message: string
}

export interface ImportResult {
  files: number
  imported: number
  skipped: number
  details: ImportFileResult[]
}

export interface ImportBatchView {
  id: number
  subjectId: number
  subjectName: string
  fileName: string
  filePath?: string
  status: string
  total: number
  imported: number
  skipped: number
  createdAt: string
}

export interface ImportHistoryGroup {
  subjectId?: number
  subjectName?: string
  path?: string
  fileName: string
  latestStatus: string
  latestAt?: string
  batchCount: number
  imported: number
  skipped: number
  batches: ImportBatchView[]
}

export interface ImportHistoryPage {
  page: number
  size: number
  totalGroups: number
  totalBatches: number
  groups: ImportHistoryGroup[]
}

export interface ImportJobView {
  id: number
  subjectId?: number
  subjectName?: string
  path?: string
  status: string
  totalFiles: number
  doneFiles: number
  currentFile?: string
  imported: number
  skipped: number
  pending: number
  message?: string
  startedAt?: string
  finishedAt?: string
  details: ImportFileResult[]
}

export interface AiConfigView {
  provider: string
  baseUrl: string
  model: string
  hasKey: boolean
  apiKeyMasked: string
  thinking: boolean
  note: string
}

export interface AiTestResult {
  ok: boolean
  message: string
  sample?: string
}

export interface AiCheckView {
  questionId: number
  stem: string
  type: string
  answer: string
  aiAnswer?: string
  verdict: string
  reason?: string
  model?: string
  status: string
  createdAt?: string
}

export interface AiProgress {
  running: boolean
  done: number
  total: number
  message: string
}

export interface AiSubjectStat {
  subjectId: number
  total: number
  unchecked: number
}

export interface WrongSubject {  subjectId: number
  name: string
  color: string
  count: number
}

export interface WrongItem {
  id: number
  type: string
  stem: string
  options: Option[]
  answer: string
  analysis?: string
  source?: string
  year?: number
  wrongCount: number
  lastWrongAt?: string
}

export interface WrongPage {
  items: WrongItem[]
  total: number
  page: number
  size: number
}

export interface SessionView {
  id: number
  subjectId: number
  subjectName: string
  mode: string
  status: string
  total: number
  answeredCount: number
  answered: Record<string, string>
  questions: QuestionView[]
  startedAt?: string
  durationMinutes?: number
}

export interface AnswerResult {
  correct: boolean
  correctAnswer: string
  analysis?: string
  correctCount: number
  answeredCount: number
}

export interface QuestionResult {
  questionId: number
  stem: string
  type: string
  correctAnswer: string
  userAnswer?: string
  correct: boolean
  answered: boolean
  analysis?: string
}

export interface ResultView {
  sessionId: number
  subjectId: number
  subjectName: string
  total: number
  correct: number
  wrong: number
  unanswered: number
  accuracy: number
  earnedScore: number
  totalScore: number
  costMs: number
  items: QuestionResult[]
  mode?: string
  durationMinutes: number
}

export interface AuthStatus {
  hasUsers: boolean
}

export interface AuthUser {
  id: number
  username: string
  admin: boolean
  token?: string
}

let activeMajorId: number | null = null

/** 当前专业：所有列表类接口自动带上 majorId，切专业时整页刷新即可 */
export function setActiveMajor(id: number | null) {
  activeMajorId = id
}

export function getActiveMajor() {
  return activeMajorId
}

/** 当前账号保存“上次选择的专业”的 localStorage key。 */
export function majorStorageKey() {
  const userId = localStorage.getItem('quizmate.userId')
  return userId ? `quizmate.majorId.${userId}` : 'quizmate.majorId'
}

function majorQs(prefix: '?' | '&' = '?') {
  return activeMajorId == null ? '' : `${prefix}majorId=${activeMajorId}`
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init
  })
  if (!res.ok) {
    let message = `HTTP ${res.status}`
    try {
      const body = await res.json()
      if (body?.error) message = body.error
    } catch {
      /* ignore */
    }
    if (res.status === 401 && !path.startsWith('/api/auth/')) {
      window.dispatchEvent(new CustomEvent('quizmate:auth-required'))
    }
    throw new Error(message)
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const api = {
  authStatus: () => request<AuthStatus>('/api/auth/status'),
  authMe: () => request<AuthUser>('/api/auth/me'),
  login: (username: string, password: string) =>
    request<AuthUser>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    }),
  register: (username: string, password: string) =>
    request<AuthUser>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    }),
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),
  majors: () => request<MajorView[]>('/api/majors'),
  setupStatus: () => request<SetupStatus>('/api/setup/status'),
  completeSetup: (body: SetupCompleteRequest) =>
    request<SetupCompleteResult>('/api/setup/complete', { method: 'POST', body: JSON.stringify(body) }),
  examPlan: () => request<ExamPlan>('/api/exam-plan' + majorQs()),
  majorCatalog: (province: string, level: string) =>
    request<MajorCatalogView>(
      `/api/majors/catalog?province=${encodeURIComponent(province)}&level=${encodeURIComponent(level)}`
    ),
  majorCourses: (params: { province: string; level: string; code?: string; name?: string }) => {
    const q = new URLSearchParams()
    q.set('province', params.province)
    q.set('level', params.level)
    if (params.code) q.set('code', params.code)
    if (params.name) q.set('name', params.name)
    return request<MajorCourseView>(`/api/majors/courses?${q.toString()}`)
  },
  majorCatalogCoverage: () => request<MajorCatalogCoverage[]>('/api/majors/catalog/coverage'),

  subjects: () => request<SubjectView[]>('/api/subjects' + majorQs()),
  overview: () => request<Overview>('/api/stats/overview' + majorQs()),
  statsReport: (days = 14) =>
    request<StatsReport>(`/api/stats/report?days=${days}${majorQs('&')}`),
  createSession: (body: {
    subjectId: number
    mode?: string
    count?: number
    type?: string
    chapterId?: number
    onlyWrong?: boolean
    onlyNew?: boolean
    shuffle?: boolean
    types?: string[]
    years?: number[]
    durationMinutes?: number
    questionIds?: number[]
  }) => request<SessionView>('/api/practice/sessions', { method: 'POST', body: JSON.stringify(body) }),
  buildOptions: (subjectId: number) => request<BuildOptions>(`/api/practice/options?subjectId=${subjectId}`),
  startReview: (limit = 30, subjectId?: number) =>
    request<SessionView>(
      `/api/practice/review?limit=${limit}${majorQs('&')}${subjectId ? `&subjectId=${subjectId}` : ''}`,
      { method: 'POST' }
    ),

  browseImport: (path?: string) =>
    request<FsListing>(`/api/import/browse${path ? `?path=${encodeURIComponent(path)}` : ''}`),
  scanImport: (body: { subjectId: number; path: string }) =>
    request<ScanResult>('/api/import/scan', { method: 'POST', body: JSON.stringify(body) }),
  importDir: (body: { subjectId: number; path: string }) =>
    request<ImportResult>('/api/import/dir', { method: 'POST', body: JSON.stringify(body) }),
  startImportJob: (body: { subjectId: number; path: string; year?: number }) =>
    request<ImportJobView>('/api/import/jobs', { method: 'POST', body: JSON.stringify(body) }),
  importJob: (id: number) => request<ImportJobView>(`/api/import/jobs/${id}`),
  cancelImportJob: (id: number) =>
    request<ImportJobView>(`/api/import/jobs/${id}/cancel`, { method: 'POST' }),
  importBatches: (subjectId?: number) =>
    request<ImportBatchView[]>(`/api/import/batches${subjectId ? `?subjectId=${subjectId}` : ''}`),
  importHistory: (params: {
    subjectId?: number
    status?: string
    q?: string
    page?: number
    size?: number
  }) => {
    const qs = new URLSearchParams()
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null && v !== '') qs.set(k, String(v))
    }
    return request<ImportHistoryPage>(`/api/import/history?${qs.toString()}`)
  },
  rollbackImport: (id: number) =>
    request<{ deleted: number }>(`/api/import/rollback/${id}`, { method: 'POST' }),
  rollbackImportGroup: (subjectId: number, path: string) =>
    request<{ deleted: number }>('/api/import/rollback-group', {
      method: 'POST',
      body: JSON.stringify({ subjectId, path })
    }),
  createSubject: (name: string, majorId?: number) =>
    request<{ id: number }>('/api/subjects', {
      method: 'POST',
      body: JSON.stringify({ name, majorId: majorId ?? activeMajorId })
    }),
  updateSubject: (id: number, body: { name?: string; code?: string; examDate?: string }) =>
    request<SubjectView>(`/api/subjects/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteSubject: (id: number) =>
    request<void>(`/api/subjects/${id}`, { method: 'DELETE' }),

  aiConfig: () => request<AiConfigView>('/api/ai/config'),
  saveAiConfig: (body: { provider?: string; baseUrl?: string; model?: string; apiKey?: string; thinking?: boolean }) =>
    request<AiConfigView>('/api/ai/config', { method: 'PUT', body: JSON.stringify(body) }),
  testAi: () => request<AiTestResult>('/api/ai/test', { method: 'POST' }),
  startAiChecks: (body: { subjectId: number; limit?: number; onlyUnchecked?: boolean }) =>
    request<{ started: boolean; total: number; message: string }>('/api/ai/checks/start', {
      method: 'POST',
      body: JSON.stringify(body)
    }),
  aiProgress: () => request<AiProgress>('/api/ai/checks/progress'),
  aiCheckStats: () => request<AiSubjectStat[]>('/api/ai/check-stats' + majorQs()),
  aiChecks: (subjectId?: number, status = 'NEW', verdict = 'DIFF') => {
    const qs = new URLSearchParams({ status })
    if (subjectId) qs.set('subjectId', String(subjectId))
    if (verdict) qs.set('verdict', verdict)
    return request<AiCheckView[]>(`/api/ai/checks?${qs}`)
  },
  applyAi: (questionId: number) =>
    request<QuestionView>(`/api/ai/checks/${questionId}/apply`, { method: 'POST' }),
  applyAllAi: (questionIds: number[], subjectId?: number) =>
    request<{ applied: number; failed: number; message: string }>('/api/ai/checks/apply-all', {
      method: 'POST',
      body: JSON.stringify({ subjectId, questionIds })
    }),
  ignoreAi: (questionId: number) =>
    request<void>(`/api/ai/checks/${questionId}/ignore`, { method: 'POST' }),
  aiCheckOne: (questionId: number, answer: string) =>
    request<{ ok: boolean; correct: boolean; aiAnswer?: string; reason?: string; message?: string }>(
      '/api/ai/check-one',
      { method: 'POST', body: JSON.stringify({ questionId, answer }) }
    ),
  aiAnalysis: (questionId: number) =>
    request<{ analysis: string; generated: boolean; message: string }>('/api/ai/analysis', {
      method: 'POST',
      body: JSON.stringify({ questionId })
    }),

  questions: (params: {
    subjectId?: number
    type?: string
    status?: string
    q?: string
    year?: number
    page?: number
    size?: number
  }) => {
    const qs = new URLSearchParams()
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null && v !== '') qs.set(k, String(v))
    }
    return request<QuestionPage>(`/api/questions?${qs}`)
  },
  updateQuestion: (id: number, body: { answer?: string; analysis?: string }) =>
    request<QuestionView>(`/api/questions/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
  deleteQuestion: (id: number) =>
    request<{ ok: boolean }>(`/api/questions/${id}`, { method: 'DELETE' }),
  getSession: (id: number) => request<SessionView>(`/api/practice/sessions/${id}`),
  answer: (id: number, body: { questionId: number; answer: string; costMs?: number; selfRating?: string }) =>
    request<AnswerResult>(`/api/practice/sessions/${id}/answers`, { method: 'POST', body: JSON.stringify(body) }),
  finish: (id: number) => request<ResultView>(`/api/practice/sessions/${id}/finish`, { method: 'POST' }),
  result: (id: number) => request<ResultView>(`/api/practice/sessions/${id}/result`),

  cardSummary: () => request<CardSummary[]>('/api/cards/summary' + majorQs()),
  cards: (subjectId: number, limit = 50) =>
    request<CardView[]>(`/api/cards?subjectId=${subjectId}&limit=${limit}`),
  reviewCard: (id: number, rating: 'MASTER' | 'VAGUE' | 'NONE') =>
    request<{ mastery: number; lastResult: string; nextDue: string }>(`/api/cards/${id}/review`, {
      method: 'POST',
      body: JSON.stringify({ rating })
    }),

  pendingQuestions: (subjectId: number, source?: string, limit = 200) => {
    const qs = new URLSearchParams({ subjectId: String(subjectId), limit: String(limit) })
    if (source) qs.set('source', source)
    return request<QuestionView[]>(`/api/questions/pending?${qs}`)
  },
  pendingSources: (subjectId: number) =>
    request<PendingSource[]>(`/api/questions/pending/sources?subjectId=${subjectId}`),
  setAnswer: (id: number, answer: string) =>
    request<{ ok: boolean }>(`/api/questions/${id}/answer`, {
      method: 'POST',
      body: JSON.stringify({ answer })
    }),
  batchAnswer: (subjectId: number, source: string, answers: string, fromOne = false) =>
    request<{ filled: number; skipped: number; message: string }>(`/api/questions/batch-answer`, {
      method: 'POST',
      body: JSON.stringify({ subjectId, source, answers, fromOne })
    }),
  answerImages: (source: string) =>
    request<string[]>(`/api/answer-images?source=${encodeURIComponent(source)}`),
  ocrAnswers: (source: string, subjectId: number, loose = false) =>
    request<{ answers: { qno: number; answer: string }[]; count: number; message: string }>(
      `/api/answer-images/ocr?source=${encodeURIComponent(source)}&subjectId=${subjectId}&loose=${loose}`
    ),

  wrongSummary: () => request<WrongSubject[]>('/api/wrong/summary' + majorQs()),
  wrongList: (params: {
    subjectId: number
    type?: string
    q?: string
    sort?: string
    page?: number
    size?: number
  }) => {
    const qs = new URLSearchParams({ subjectId: String(params.subjectId) })
    if (params.type) qs.set('type', params.type)
    if (params.q) qs.set('q', params.q)
    if (params.sort) qs.set('sort', params.sort)
    if (params.page) qs.set('page', String(params.page))
    if (params.size) qs.set('size', String(params.size))
    return request<WrongPage>(`/api/wrong?${qs.toString()}`)
  }
}

export const TYPE_NAME: Record<string, string> = {
  SINGLE: '单选题',
  MULTIPLE: '多选题',
  JUDGE: '判断题',
  FILL: '填空题',
  TERM: '名词解释',
  SHORT: '简答题',
  CALC: '计算题',
  ESSAY: '论述题'
}

export function isSubjective(type: string): boolean {
  return ['TERM', 'SHORT', 'CALC', 'ESSAY'].includes(type)
}
