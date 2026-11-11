const { request, toastError } = require('../../utils/request')
const { examBadge, todayText } = require('../../utils/format')

Page({
  data: {
    loading: true,
    overview: null,
    plan: null,
    activeSubjects: [],
    idleSubjects: [],
    today: '',
    greeting: '',
    nextNames: '',
    pullDown: false
  },

  onShow() {
    if (!wx.getStorageSync('quizmate.token')) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.setData({
      today: todayText(),
      greeting: this.greetingText()
    })
    this.load()
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  greetingText() {
    const h = new Date().getHours()
    if (h < 6) return '夜深了，翻两页就休息'
    if (h < 11) return '早上好，今天也要加油'
    if (h < 14) return '中午好，来做几道题'
    if (h < 18) return '下午好，保持这个节奏'
    return '晚上好，今天再进步一点'
  },

  async load() {
    this.setData({ loading: true })
    try {
      const [subjects, overview, plan] = await Promise.all([
        request('/api/subjects'),
        request('/api/stats/overview'),
        request('/api/exam-plan')
      ])
      const active = (subjects || [])
        .filter((s) => s.questionCount > 0)
        .map((s) =>
          Object.assign({}, s, {
            badge: examBadge(s.examDate),
            initial: (s.name || '').trim().slice(0, 1)
          })
        )
      const idle = (subjects || []).filter(
        (s) => s.questionCount === 0 && !!s.examDate
      )
      let nextNames = ''
      if (plan && plan.nextExamDate) {
        const group = (plan.groups || []).find((g) => g.examDate === plan.nextExamDate)
        const names = group ? group.subjects.map((s) => s.name) : []
        nextNames =
          names.length <= 3
            ? names.join('、')
            : names.slice(0, 3).join('、') + ' 等 ' + names.length + ' 门'
      }
      this.setData({
        overview,
        plan,
        activeSubjects: active,
        idleSubjects: idle,
        nextNames
      })
    } catch (e) {
      if (e.message !== '登录已失效，请重新登录') {
        toastError(e)
      }
    } finally {
      this.setData({ loading: false })
    }
  },

  startPractice(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/practice/practice?subjectId=' + id })
  },

  goPlanTip() {
    wx.navigateTo({ url: '/pages/plan/plan' })
  },

  goPlan() {
    wx.navigateTo({ url: '/pages/plan/plan' })
  },

  goStats() {
    wx.navigateTo({ url: '/pages/stats/stats' })
  },

  goBank(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/bank/bank?subjectId=' + id })
  },

  goBankAll() {
    wx.navigateTo({ url: '/pages/bank/bank' })
  },

  goCards() {
    wx.navigateTo({ url: '/pages/cards/cards' })
  },

  goExam() {
    wx.navigateTo({ url: '/pages/exam/exam' })
  },

  goAi() {
    wx.navigateTo({ url: '/pages/ai/ai' })
  },

  goImport(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/import/import?subjectId=' + id })
  },

  goWrong() {
    wx.switchTab({ url: '/pages/wrong/wrong' })
  }
})
