const { API_BASE } = require('../config')

function request(path, options = {}) {
  return new Promise((resolve, reject) => {
    const token = wx.getStorageSync('quizmate.token')
    const header = Object.assign(
      { 'Content-Type': 'application/json' },
      options.header || {}
    )
    if (token) {
      header.Authorization = 'Bearer ' + token
    }
    wx.request({
      url: API_BASE + path,
      method: options.method || 'GET',
      data: options.data,
      header,
      timeout: 30000,
      success(res) {
        if (res.statusCode === 401) {
          wx.removeStorageSync('quizmate.token')
          wx.reLaunch({ url: '/pages/login/login' })
          reject(new Error('登录已失效，请重新登录'))
          return
        }
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res.data)
          return
        }
        const message =
          (res.data && res.data.error) || '请求失败（HTTP ' + res.statusCode + '）'
        reject(new Error(message))
      },
      fail(err) {
        reject(new Error((err && err.errMsg) || '网络连接失败'))
      }
    })
  })
}

function toastError(err) {
  wx.showToast({
    title: (err && err.message) || '操作失败',
    icon: 'none',
    duration: 2500
  })
}

module.exports = { request, toastError }
