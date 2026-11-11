App({
  globalData: {
    user: null
  },

  onLaunch() {
    const token = wx.getStorageSync('quizmate.token')
    if (token) {
      this.globalData.token = token
    }
  },

  setToken(token) {
    this.globalData.token = token
    if (token) {
      wx.setStorageSync('quizmate.token', token)
    } else {
      wx.removeStorageSync('quizmate.token')
    }
  },

  logout() {
    this.setToken('')
    this.globalData.user = null
    wx.reLaunch({ url: '/pages/login/login' })
  }
})
