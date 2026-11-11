const { request, toastError } = require('../../utils/request')
const { API_BASE } = require('../../config')

Page({
  data: {
    loading: true,
    subjects: [],
    subjectNames: [],
    subjectIndex: 0,
    activeSubjectId: null,
    activeSubjectName: '',
    file: null,
    fileName: '',
    fileSizeText: '',
    uploading: false,
    uploadPct: 0,
    result: null,
    error: ''
  },

  onLoad(query) {
    this.wantedSubjectId = query && query.subjectId ? Number(query.subjectId) : null
  },

  onShow() {
    if (!wx.getStorageSync('quizmate.token')) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    if (!this.data.subjects.length) this.loadSubjects()
  },

  async loadSubjects() {
    this.setData({ loading: true })
    try {
      const subjects = await request('/api/subjects')
      const list = subjects || []
      const lastImport = wx.getStorageSync('quizmate.lastImportSubjectId')
      let index = this.wantedSubjectId
        ? list.findIndex((s) => s.id === this.wantedSubjectId)
        : lastImport
          ? list.findIndex((s) => s.id === lastImport)
          : -1
      if (index < 0) index = list.findIndex((s) => s.questionCount === 0 && s.examDate)
      if (index < 0) index = list.findIndex((s) => s.questionCount > 0)
      if (index < 0) index = 0
      const active = list[index]
      this.setData({
        subjects: list,
        subjectNames: list.map((s) => s.name),
        subjectIndex: index,
        activeSubjectId: active ? active.id : null,
        activeSubjectName: active ? active.name : ''
      })
      if (active) wx.setStorageSync('quizmate.lastImportSubjectId', active.id)
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ loading: false })
    }
  },

  pickSubject(e) {
    const index = Number(e.detail.value)
    const active = this.data.subjects[index]
    if (!active) return
    this.setData({
      subjectIndex: index,
      activeSubjectId: active.id,
      activeSubjectName: active.name
    })
    wx.setStorageSync('quizmate.lastImportSubjectId', active.id)
  },

  chooseFile() {
    wx.chooseMessageFile({
      count: 1,
      type: 'file',
      extension: ['doc', 'docx', 'pdf', 'pptx', 'txt', 'md', 'xlsx', 'xls'],
      success: (res) => {
        const f = res.tempFiles && res.tempFiles[0]
        if (!f) return
        this.setData({
          file: { path: f.path, name: f.name, size: f.size },
          fileName: f.name,
          fileSizeText: this.sizeText(f.size),
          result: null,
          error: ''
        })
      },
      fail: () => {
        wx.showToast({ title: '没有选择文件', icon: 'none' })
      }
    })
  },

  sizeText(size) {
    if (!size) return ''
    if (size < 1024 * 1024) return Math.round(size / 1024) + ' KB'
    return (size / 1024 / 1024).toFixed(1) + ' MB'
  },

  upload() {
    if (this.data.uploading) return
    if (!this.data.activeSubjectId) {
      wx.showToast({ title: '先选择学科', icon: 'none' })
      return
    }
    if (!this.data.file) {
      wx.showToast({ title: '先选择文件', icon: 'none' })
      return
    }
    const token = wx.getStorageSync('quizmate.token')
    this.setData({ uploading: true, uploadPct: 0, result: null, error: '' })
    const task = wx.uploadFile({
      url: API_BASE + '/api/import/upload',
      filePath: this.data.file.path,
      name: 'file',
      formData: { subjectId: String(this.data.activeSubjectId) },
      header: token ? { Authorization: 'Bearer ' + token } : {},
      success: (res) => {
        if (res.statusCode === 401) {
          wx.removeStorageSync('quizmate.token')
          wx.reLaunch({ url: '/pages/login/login' })
          return
        }
        let body = null
        try {
          body = typeof res.data === 'string' ? JSON.parse(res.data) : res.data
        } catch (e) {
          body = null
        }
        if (res.statusCode >= 200 && res.statusCode < 300) {
          this.setData({ result: body })
          wx.showToast({
            title: body && body.imported > 0 ? '导入完成' : '没有新增题目',
            icon: body && body.imported > 0 ? 'success' : 'none'
          })
        } else {
          this.setData({
            error: (body && body.error) || '导入失败（HTTP ' + res.statusCode + '）'
          })
        }
      },
      fail: (err) => {
        this.setData({ error: (err && err.errMsg) || '上传失败' })
      },
      complete: () => {
        this.setData({ uploading: false })
      }
    })
    if (task && task.onProgressUpdate) {
      task.onProgressUpdate((p) => {
        this.setData({ uploadPct: p.progress || 0 })
      })
    }
  },

  reset() {
    this.setData({
      file: null,
      fileName: '',
      fileSizeText: '',
      result: null,
      error: '',
      uploadPct: 0
    })
  },

  goBank() {
    wx.navigateTo({ url: '/pages/bank/bank?subjectId=' + this.data.activeSubjectId })
  },

  goHome() {
    wx.switchTab({ url: '/pages/home/home' })
  }
})
