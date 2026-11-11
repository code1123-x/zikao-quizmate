const { request, toastError } = require('../../utils/request')

Page({
  data: {
    loading: true,
    saving: false,
    major: null,
    subjects: [],
    commonDate: '',
    selectedCount: 0,
    nextExamDate: '',
    planCount: 0
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
      const majors = await request('/api/majors')
      if (!majors || !majors.length) {
        this.setData({ loading: false })
        wx.showToast({ title: '还没有专业，请先在网页端设置', icon: 'none' })
        return
      }
      const savedId = wx.getStorageSync('quizmate.majorId')
      const major = majors.find((m) => m.id === savedId) || majors[0]
      wx.setStorageSync('quizmate.majorId', major.id)
      const [subjects, plan] = await Promise.all([
        request('/api/subjects?majorId=' + major.id),
        request('/api/exam-plan?majorId=' + major.id)
      ])
      const dates = (subjects || [])
        .map((s) => s.examDate)
        .filter(Boolean)
        .sort()
      const commonDate = dates.length ? dates[0] : this.defaultExamDate()
      const rows = (subjects || []).map((s) => ({
        id: s.id,
        name: s.name,
        code: s.code,
        questionCount: s.questionCount,
        choiceCount: s.choiceCount,
        selected: !!s.examDate,
        examDate: s.examDate || commonDate,
        initial: (s.name || '').slice(0, 1),
        color: s.color
      }))
      this.setData({
        major,
        subjects: rows,
        commonDate,
        selectedCount: rows.filter((r) => r.selected).length,
        nextExamDate: plan && plan.nextExamDate ? plan.nextExamDate : '',
        planCount: plan && plan.plannedCount != null ? plan.plannedCount : 0
      })
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ loading: false })
    }
  },

  toggleSubject(e) {
    const index = Number(e.currentTarget.dataset.index)
    const subjects = this.data.subjects.slice()
    const row = Object.assign({}, subjects[index])
    row.selected = e.detail.value
    if (row.selected && !row.examDate) row.examDate = this.data.commonDate
    subjects[index] = row
    this.setData({
      subjects,
      selectedCount: subjects.filter((s) => s.selected).length
    })
  },

  onCommonDate(e) {
    const commonDate = e.detail.value
    const subjects = this.data.subjects.map((s) =>
      s.selected ? Object.assign({}, s, { examDate: commonDate }) : s
    )
    this.setData({ commonDate, subjects })
  },

  onSubjectDate(e) {
    const index = Number(e.currentTarget.dataset.index)
    const subjects = this.data.subjects.slice()
    subjects[index] = Object.assign({}, subjects[index], { examDate: e.detail.value })
    this.setData({ subjects })
  },

  selectAll() {
    const subjects = this.data.subjects.map((s) =>
      Object.assign({}, s, { selected: true, examDate: s.examDate || this.data.commonDate })
    )
    this.setData({
      subjects,
      selectedCount: subjects.length
    })
  },

  clearAll() {
    const subjects = this.data.subjects.map((s) => Object.assign({}, s, { selected: false }))
    this.setData({ subjects, selectedCount: 0 })
  },

  async save() {
    if (this.data.saving) return
    const selected = this.data.subjects.filter((s) => s.selected)
    if (!selected.length) {
      wx.showToast({ title: '至少选择一门本次要考的学科', icon: 'none' })
      return
    }
    this.setData({ saving: true })
    try {
      const body = {
        majorId: this.data.major.id,
        majorName: this.data.major.name,
        majorCode: this.data.major.code,
        province: this.data.major.province,
        level: this.data.major.level,
        subjects: this.data.subjects.map((s) => ({
          id: s.id,
          name: s.name,
          code: s.code,
          selected: s.selected,
          examDate: s.selected ? s.examDate || this.data.commonDate : undefined
        }))
      }
      await request('/api/setup/complete', { method: 'POST', data: body })
      wx.showToast({ title: '考试计划已保存', icon: 'success' })
      setTimeout(() => wx.navigateBack(), 600)
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ saving: false })
    }
  },

  webTip() {
    wx.showModal({
      title: '换专业 / 省份',
      content: '换专业、省份、层次请先在网页端操作；小程序端负责勾选本次要考的学科和日期。',
      showCancel: false
    })
  },

  defaultExamDate() {
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    for (let y = today.getFullYear(); y <= today.getFullYear() + 2; y++) {
      for (const m of [4, 10]) {
        const d = new Date(y, m - 1, 15)
        if (d >= today) return this.fmtDate(d)
      }
    }
    return this.fmtDate(new Date(today.getFullYear() + 1, 3, 15))
  },

  fmtDate(d) {
    return (
      d.getFullYear() +
      '-' +
      String(d.getMonth() + 1).padStart(2, '0') +
      '-' +
      String(d.getDate()).padStart(2, '0')
    )
  }
})
