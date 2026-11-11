const { request, toastError } = require('../../utils/request')

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
    days: 14,
    rangeDays: [7, 14, 30],
    report: null,
    overview: null,
    bars: [],
    subjects: [],
    types: [],
    starting: false,
    minutesText: '0 分钟',
    todayText: ''
  },

  onShow() {
    if (!wx.getStorageSync('quizmate.token')) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.load()
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  setDays(e) {
    const days = Number(e.currentTarget.dataset.days)
    if (days === this.data.days) return
    this.setData({ days }, () => this.load())
  },

  async load() {
    this.setData({ loading: true })
    try {
      const report = await request('/api/stats/report?days=' + this.data.days)
      const chart = this.buildChart(report.daily || [], this.data.days)
      const max = Math.max(1, ...chart.map((d) => d.answered))
      const bars = chart.map((d) => ({
        label: d.label,
        answered: d.answered,
        correct: d.correct,
        h: d.answered ? Math.max(6, Math.round((d.answered / max) * 180)) : 2,
        okH: d.answered ? Math.round((d.correct / d.answered) * Math.max(6, Math.round((d.answered / max) * 180))) : 0
      }))
      const subjects = (report.subjects || [])
        .filter((s) => s.choiceCount > 0 || s.answered > 0)
        .map((s) => Object.assign({}, s, { initial: (s.name || '').slice(0, 1) }))
      const types = (report.types || []).map((t) =>
        Object.assign({}, t, { label: TYPE_LABEL[t.type] || t.type })
      )
      this.setData({
        report,
        overview: report.overview,
        bars,
        subjects,
        types,
        minutesText: this.minutesText(report.overview.minutes),
        todayText: `今天 ${report.overview.todayAnswered} 题 · 正确 ${report.overview.todayCorrect} 题`
      })
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ loading: false })
    }
  },

  buildChart(list, days) {
    if (days !== 30) {
      return list.map((d) => ({
        label: d.date.slice(5),
        answered: d.answered,
        correct: d.correct
      }))
    }
    const groups = []
    for (let i = 0; i < list.length; i += 7) {
      const chunk = list.slice(i, i + 7)
      if (!chunk.length) continue
      groups.push({
        label: chunk[0].date.slice(5) + '~' + chunk[chunk.length - 1].date.slice(5),
        answered: chunk.reduce((a, d) => a + d.answered, 0),
        correct: chunk.reduce((a, d) => a + d.correct, 0)
      })
    }
    return groups
  },

  minutesText(m) {
    if (!m) return '0 分钟'
    if (m < 60) return m + ' 分钟'
    return Math.floor(m / 60) + ' 小时 ' + (m % 60) + ' 分'
  },

  async reviewSubject(e) {
    const subjectId = Number(e.currentTarget.dataset.id)
    if (!subjectId || this.data.starting) return
    this.setData({ starting: true })
    try {
      const session = await request('/api/practice/review?limit=20&subjectId=' + subjectId)
      wx.navigateTo({ url: '/pages/practice/practice?sessionId=' + session.id })
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ starting: false })
    }
  }
})
