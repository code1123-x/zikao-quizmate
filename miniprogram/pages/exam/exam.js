const { request, toastError } = require('../../utils/request')

Page({
  data: {
    loading: true,
    starting: false,
    subjects: [],
    subjectNames: [],
    subjectIndex: 0,
    active: null,
    mode: 'EXAM',
    counts: [20, 50, 100],
    countIndex: 0,
    durations: [30, 60, 90, 120],
    durationIndex: 1
  },

  onShow() {
    if (!wx.getStorageSync('quizmate.token')) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.load()
  },

  async load() {
    this.setData({ loading: true })
    try {
      const all = await request('/api/subjects')
      const subjects = (all || []).filter((s) => s.choiceCount > 0)
      let index = subjects.findIndex((s) => s.id === wx.getStorageSync('quizmate.lastSubjectId'))
      if (index < 0) index = 0
      this.setData(
        {
          subjects,
          subjectNames: subjects.map((s) => s.name),
          subjectIndex: index,
          active: subjects[index] || null
        },
        () => {
          if (subjects[index]) wx.setStorageSync('quizmate.lastSubjectId', subjects[index].id)
        }
      )
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ loading: false })
    }
  },

  pickSubject(e) {
    const index = Number(e.detail.value)
    const active = this.data.subjects[index]
    this.setData({ subjectIndex: index, active })
    if (active) wx.setStorageSync('quizmate.lastSubjectId', active.id)
  },

  setMode(e) {
    this.setData({ mode: e.currentTarget.dataset.mode })
  },

  setCount(e) {
    this.setData({ countIndex: Number(e.currentTarget.dataset.index) })
  },

  setDuration(e) {
    this.setData({ durationIndex: Number(e.currentTarget.dataset.index) })
  },

  async start() {
    if (this.data.starting || !this.data.active) return
    this.setData({ starting: true })
    try {
      const body = {
        subjectId: this.data.active.id,
        mode: this.data.mode,
        count: this.data.counts[this.data.countIndex],
        shuffle: true
      }
      if (this.data.mode === 'EXAM') {
        body.durationMinutes = this.data.durations[this.data.durationIndex]
      }
      const session = await request('/api/practice/sessions', {
        method: 'POST',
        data: body
      })
      wx.navigateTo({ url: '/pages/practice/practice?sessionId=' + session.id })
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ starting: false })
    }
  }
})
