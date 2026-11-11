const { request, toastError } = require('../../utils/request')

const COUNT = 20
const TYPE_LABEL = {
  SINGLE: '单选',
  MULTIPLE: '多选',
  JUDGE: '判断',
  FILL: '填空',
  TERM: '名词解释',
  SHORT: '简答',
  CALC: '计算',
  ESSAY: '论述'
}

Page({
  data: {
    loading: true,
    session: null,
    questions: [],
    index: 0,
    total: 0,
    answeredCount: 0,
    progress: 0,
    current: null,
    typeLabel: '',
    options: [],
    optionViews: [],
    selectedKeys: [],
    judged: false,
    result: null,
    submitting: false,
    finished: false,
    summary: null,
    unsupported: false,
    isExam: false,
    durationMinutes: 0,
    remainingText: '',
    examSaved: false,
    finishing: false,
    textAnswer: '',
    showReference: false,
    selfRating: ''
  },

  onLoad(query) {
    if (query.sessionId) {
      this.loadSession(Number(query.sessionId))
    } else if (query.subjectId) {
      this.createSession(Number(query.subjectId))
    } else {
      wx.navigateBack()
    }
  },

  draftKey() {
    return 'quizmate.practiceDraft.' + (this.data.session && this.data.session.id)
  },

  saveDraft() {
    if (!this.data.session) return
    wx.setStorageSync(this.draftKey(), {
      index: this.data.index,
      selectedKeys: this.data.selectedKeys,
      textAnswer: this.data.textAnswer
    })
  },

  clearDraft() {
    if (this.data.session) wx.removeStorageSync(this.draftKey())
  },

  onUnload() {
    this.saveDraft()
    this.clearExamTimer()
  },

  async createSession(subjectId) {
    this.setData({ loading: true })
    try {
      const session = await request('/api/practice/sessions', {
        method: 'POST',
        data: { subjectId, mode: 'RANDOM', count: COUNT, shuffle: true }
      })
      this.applySession(session)
    } catch (e) {
      toastError(e)
      setTimeout(() => wx.navigateBack(), 1500)
    }
  },

  async loadSession(id) {
    this.setData({ loading: true })
    try {
      const session = await request('/api/practice/sessions/' + id)
      this.applySession(session)
    } catch (e) {
      toastError(e)
      setTimeout(() => wx.navigateBack(), 1500)
    }
  },

  applySession(session) {
    const questions = session.questions || []
    const answered = session.answered || {}
    const draft = wx.getStorageSync('quizmate.practiceDraft.' + session.id) || {}
    let index = Number.isInteger(draft.index) && draft.index >= 0 && draft.index < questions.length
      ? draft.index
      : questions.findIndex((q) => answered[q.id] === undefined)
    if (index < 0) index = 0
    const isExam = session.mode === 'EXAM'
    this.setData(
      {
        loading: false,
        session,
        questions,
        total: session.total || questions.length,
        answeredCount: Object.keys(answered).length,
        index,
        isExam,
        durationMinutes: session.durationMinutes || 0
      },
      () => {
        this.build()
        if (Array.isArray(draft.selectedKeys) || typeof draft.textAnswer === 'string') {
          this.setData({
            selectedKeys: Array.isArray(draft.selectedKeys) ? draft.selectedKeys : [],
            textAnswer: typeof draft.textAnswer === 'string' ? draft.textAnswer : ''
          }, () => this.refreshOptions())
        }
        if (isExam) this.startExamTimer()
      }
    )
  },

  startExamTimer() {
    this.clearExamTimer()
    const session = this.data.session
    if (!session || !session.startedAt || !this.data.durationMinutes) return
    const end = Date.parse(session.startedAt) + this.data.durationMinutes * 60000
    const tick = () => {
      const left = Math.max(0, Math.round((end - Date.now()) / 1000))
      this.setData({ remainingText: this.formatSeconds(left) })
      if (left <= 0) {
        this.clearExamTimer()
        if (!this.data.finished) {
          wx.showToast({ title: '考试时间到，已自动交卷', icon: 'none' })
          this.finish()
        }
      }
    }
    tick()
    this.examTimer = setInterval(tick, 1000)
  },

  clearExamTimer() {
    if (this.examTimer) {
      clearInterval(this.examTimer)
      this.examTimer = null
    }
  },

  formatSeconds(total) {
    const h = Math.floor(total / 3600)
    const m = Math.floor((total % 3600) / 60)
    const s = total % 60
    if (h > 0) {
      return h + ':' + String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0')
    }
    return String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0')
  },

  build() {
    const q = this.data.questions[this.data.index]
    if (!q) {
      this.setData({ current: null })
      return
    }
    let options = q.options || []
    if (q.type === 'JUDGE' && !options.length) {
      options = [
        { key: '对', text: '正确' },
        { key: '错', text: '错误' }
      ]
    }
    const answered = this.data.session.answered || {}
    const stored = answered[q.id]
    const selectedKeys = stored
      ? q.type === 'MULTIPLE'
        ? String(stored).split('')
        : [String(stored)]
      : []
    const unsupported = false
    this.startedAt = Date.now()
    this.setData(
      {
        current: q,
        typeLabel: TYPE_LABEL[q.type] || q.type,
        options,
        selectedKeys,
        judged: false,
        result: null,
        unsupported,
        textAnswer: '',
        showReference: false,
        selfRating: '',
        examSaved: !!stored,
        progress: this.data.total
          ? Math.round((this.data.answeredCount / this.data.total) * 100)
          : 0
      },
      () => this.refreshOptions()
    )
  },

  refreshOptions() {
    const { options, selectedKeys, judged, result } = this.data
    const correct = judged && result ? result.correctAnswer || '' : ''
    const optionViews = options.map((o) => {
      const picked = selectedKeys.indexOf(o.key) >= 0
      return {
        key: o.key,
        text: o.text,
        picked,
        correct: judged && correct.indexOf(o.key) >= 0,
        wrong: judged && picked && correct.indexOf(o.key) < 0
      }
    })
    this.setData({ optionViews })
  },

  pickOption(e) {
    if (this.data.judged || this.data.submitting || this.data.unsupported) return
    const key = e.currentTarget.dataset.key
    const q = this.data.current
    if (!q) return
    if (q.type === 'MULTIPLE') {
      const keys = this.data.selectedKeys.slice()
      const i = keys.indexOf(key)
      if (i >= 0) keys.splice(i, 1)
      else keys.push(key)
      this.setData(
        { selectedKeys: keys, examSaved: this.data.isExam ? false : this.data.examSaved },
        () => { this.refreshOptions(); this.saveDraft() }
      )
    } else {
      this.setData(
        { selectedKeys: [key], examSaved: this.data.isExam ? false : this.data.examSaved },
        () => { this.refreshOptions(); this.saveDraft() }
      )
    }
  },

  onTextAnswer(e) {
    this.setData({ textAnswer: e.detail.value }, () => this.saveDraft())
  },

  toggleReference() {
    this.setData({ showReference: !this.data.showReference })
  },

  async submitAnswer(rating) {
    if (this.data.submitting || this.data.judged) return
    const q = this.data.current
    const keys = this.data.selectedKeys
    if (!q || (!keys.length && !this.data.textAnswer.trim() && !rating)) {
      wx.showToast({ title: '请先填写答案或选择选项', icon: 'none' })
      return
    }
    const answer = ['SINGLE', 'MULTIPLE', 'JUDGE'].includes(q.type)
      ? q.type === 'MULTIPLE' ? keys.slice().sort().join('') : keys[0]
      : this.data.textAnswer.trim() || '（未作答）'
    this.setData({ submitting: true })
    try {
      const result = await request(
        '/api/practice/sessions/' + this.data.session.id + '/answers',
        {
          method: 'POST',
          data: {
            questionId: q.id,
            answer,
            selfRating: ['SINGLE', 'MULTIPLE', 'JUDGE'].includes(q.type) ? undefined : rating,
            costMs: Math.max(0, Date.now() - this.startedAt)
          }
        }
      )
      if (this.data.isExam) {
        // 考试模式不显示对错，只记录已答，交卷后统一看结果
        const answered = Object.assign({}, this.data.session.answered || {}, {
          [q.id]: answer
        })
        this.setData(
          {
            examSaved: true,
            answeredCount: result.answeredCount,
            'session.answered': answered,
            progress: this.data.total
              ? Math.round((result.answeredCount / this.data.total) * 100)
              : 0
          },
          () => this.refreshOptions()
        )
      } else {
        this.setData(
          {
            judged: true,
            result,
            answeredCount: result.answeredCount,
            progress: this.data.total
              ? Math.round((result.answeredCount / this.data.total) * 100)
              : 0
          },
          () => this.refreshOptions()
        )
      }
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ submitting: false })
    }
  },

  rateSubjective(e) {
    this.submitAnswer(e.currentTarget.dataset.rating)
  },

  nextQuestion() {
    if (this.data.index + 1 < this.data.questions.length) {
      this.setData({ index: this.data.index + 1 }, () => this.build())
    } else {
      this.finish()
    }
  },

  skipQuestion() {
    this.nextQuestion()
  },

  async finish() {
    if (this.data.finishing || this.data.finished) return
    this.setData({ finishing: true })
    this.clearExamTimer()
    try {
      wx.showLoading({ title: '正在交卷' })
      const summary = await request(
        '/api/practice/sessions/' + this.data.session.id + '/finish',
        { method: 'POST' }
      )
      this.setData({ finished: true, summary })
      this.clearDraft()
    } catch (e) {
      toastError(e)
      this.setData({ finishing: false })
    } finally {
      wx.hideLoading()
    }
  },

  confirmFinish() {
    if (!this.data.isExam || this.data.finished || this.data.finishing || this.data.submitting) return
    const left = this.data.questions.length - this.data.answeredCount
    wx.showModal({
      title: '交卷',
      content: left > 0 ? '还有 ' + left + ' 题没答。点击“取消”先回到第一道未答题；点击“确定”仍要交卷吗？' : '确定交卷吗？',
      success: (res) => {
        if (res.confirm) {
          this.finish()
          return
        }
        if (left > 0) {
          const answered = this.data.session.answered || {}
          const first = this.data.questions.findIndex((q) => answered[q.id] === undefined || answered[q.id] === null || answered[q.id] === '')
          if (first >= 0) this.setData({ index: first }, () => this.build())
        }
      }
    })
  },

  restart() {
    if (this.data.isExam) {
      wx.redirectTo({ url: '/pages/exam/exam' })
      return
    }
    const subjectId = this.data.session && this.data.session.subjectId
    wx.redirectTo({ url: '/pages/practice/practice?subjectId=' + subjectId })
  },

  backHome() {
    wx.switchTab({ url: '/pages/home/home' })
  }
})
