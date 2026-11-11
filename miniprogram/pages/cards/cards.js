const { request, toastError } = require('../../utils/request')

Page({
  data: {
    loading: true,
    list: []
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

  async load() {
    this.setData({ loading: true })
    try {
      const summary = await request('/api/cards/summary')
      const list = (summary || [])
        .filter((s) => s.total > 0)
        .map((s) =>
          Object.assign({}, s, {
            initial: (s.name || '').slice(0, 1),
            pct: s.total ? Math.round((s.mastered / s.total) * 100) : 0
          })
        )
      this.setData({ list })
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ loading: false })
    }
  },

  start(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/learn/learn?subjectId=' + id })
  }
})
