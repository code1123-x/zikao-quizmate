const { request, toastError } = require('../../utils/request')
Page({
  data: {
    user: null,
    initial: '?',
    loading: true
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
      const user = await request('/api/auth/me')
      this.setData({
        user,
        initial: user && user.username ? user.username.slice(0, 1) : '?'
      })
    } catch (e) {
      if (e.message !== '登录已失效，请重新登录') {
        toastError(e)
      }
    } finally {
      this.setData({ loading: false })
    }
  },

  goStats() {
    wx.navigateTo({ url: '/pages/stats/stats' })
  },

  goPlan() {
    wx.navigateTo({ url: '/pages/plan/plan' })
  },

  goBank() {
    wx.navigateTo({ url: '/pages/bank/bank' })
  },

  goImport() {
    wx.navigateTo({ url: '/pages/import/import' })
  },

  goCards() {
    wx.navigateTo({ url: '/pages/cards/cards' })
  },

  goExam() {
    wx.navigateTo({ url: '/pages/exam/exam' })
  },

  logout() {
    wx.showModal({
      title: '退出登录',
      content: '确定要退出当前账号吗？',
      success: async (res) => {
        if (!res.confirm) return
        try {
          await request('/api/auth/logout', { method: 'POST' })
        } catch (e) {
          // 即使服务端失败也清除本地登录
        }
        getApp().logout()
      }
    })
  }
})
