const { request, toastError } = require('../../utils/request')

Page({
  data: {
    mode: 'login',
    username: '',
    password: '',
    loading: false,
    checking: true
  },

  onLoad() {
    this.checkLogin()
  },

  async checkLogin() {
    const token = wx.getStorageSync('quizmate.token')
    if (!token) {
      this.setData({ checking: false })
      return
    }
    try {
      await request('/api/auth/me')
      wx.switchTab({ url: '/pages/home/home' })
    } catch (e) {
      wx.removeStorageSync('quizmate.token')
      this.setData({ checking: false })
    }
  },

  onUsername(e) {
    this.setData({ username: e.detail.value })
  },

  onPassword(e) {
    this.setData({ password: e.detail.value })
  },

  switchMode() {
    this.setData({
      mode: this.data.mode === 'login' ? 'register' : 'login'
    })
  },

  async submit() {
    if (this.data.loading) return
    const username = this.data.username.trim()
    const password = this.data.password
    if (!username || !password) {
      wx.showToast({ title: '请填写账号和密码', icon: 'none' })
      return
    }
    if (this.data.mode === 'register' && password.length < 6) {
      wx.showToast({ title: '密码至少 6 位', icon: 'none' })
      return
    }
    this.setData({ loading: true })
    try {
      const path =
        this.data.mode === 'register' ? '/api/auth/register' : '/api/auth/login'
      const user = await request(path, {
        method: 'POST',
        header: { 'X-Client': 'mini' },
        data: { username, password }
      })
      if (user && user.token) {
        wx.setStorageSync('quizmate.token', user.token)
      }
      getApp().globalData.user = user
      wx.switchTab({ url: '/pages/home/home' })
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ loading: false })
    }
  }
})
