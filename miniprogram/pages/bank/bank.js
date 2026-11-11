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

const TYPES = [
  { key: '', label: '全部' },
  { key: 'SINGLE', label: '单选' },
  { key: 'MULTIPLE', label: '多选' },
  { key: 'JUDGE', label: '判断' },
  { key: 'FILL', label: '填空' },
  { key: 'TERM', label: '名词解释' },
  { key: 'SHORT', label: '简答' },
  { key: 'CALC', label: '计算' },
  { key: 'ESSAY', label: '论述' }
]

const STATUSES = [
  { key: '', label: '全部' },
  { key: 'ACTIVE', label: '有答案' },
  { key: 'NO_ANSWER', label: '待补答案' }
]

Page({
  data: {
    loading: true,
    loadingMore: false,
    subjects: [],
    subjectNames: [],
    subjectIndex: 0,
    activeSubjectId: null,
    activeSubjectName: '',
    types: TYPES,
    statuses: STATUSES,
    typeKey: '',
    statusKey: '',
    keyword: '',
    questions: [],
    total: 0,
    page: 1,
    size: 20,
    hasMore: false,
    selectedCount: 0,
    detail: null,
    editAnswer: '',
    editAnalysis: '',
    saving: false,
    deleting: false
  },

  onLoad(query) {
    this.wantedSubjectId = query && query.subjectId ? Number(query.subjectId) : null
  },

  onShow() {
    if (!wx.getStorageSync('quizmate.token')) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    if (!this.data.subjects.length) {
      this.loadSubjects()
    }
  },

  onPullDownRefresh() {
    this.loadQuestions(1, true).finally(() => wx.stopPullDownRefresh())
  },

  async loadSubjects() {
    try {
      const subjects = await request('/api/subjects')
      const list = subjects || []
      const names = list.map((s) => s.name)
      let index = this.wantedSubjectId
        ? list.findIndex((s) => s.id === this.wantedSubjectId)
        : list.findIndex((s) => s.questionCount > 0)
      if (index < 0) index = 0
      const active = list[index]
      this.setData(
        {
          subjects: list,
          subjectNames: names,
          subjectIndex: index,
          activeSubjectId: active ? active.id : null,
          activeSubjectName: active ? active.name : ''
        },
        () => {
          if (active) this.loadQuestions(1, true)
          else this.setData({ loading: false })
        }
      )
    } catch (e) {
      this.setData({ loading: false })
      toastError(e)
    }
  },

  pickSubject(e) {
    const index = Number(e.detail.value)
    const active = this.data.subjects[index]
    if (!active) return
    this.setData(
      {
        subjectIndex: index,
        activeSubjectId: active.id,
        activeSubjectName: active.name,
        questions: [],
        page: 1
      },
      () => this.loadQuestions(1, true)
    )
  },

  setType(e) {
    const key = e.currentTarget.dataset.key
    if (key === this.data.typeKey) return
    this.setData({ typeKey: key }, () => this.loadQuestions(1, true))
  },

  setStatus(e) {
    const key = e.currentTarget.dataset.key
    if (key === this.data.statusKey) return
    this.setData({ statusKey: key }, () => this.loadQuestions(1, true))
  },

  onKeyword(e) {
    this.setData({ keyword: e.detail.value })
  },

  search() {
    this.loadQuestions(1, true)
  },

  clearSearch() {
    this.setData({ keyword: '' }, () => this.loadQuestions(1, true))
  },

  async loadQuestions(page, replace) {
    if (!this.data.activeSubjectId) return
    if (!replace) this.setData({ loadingMore: true })
    if (replace) this.setData({ loading: true })
    try {
      const params = [
        'subjectId=' + this.data.activeSubjectId,
        'page=' + page,
        'size=' + this.data.size
      ]
      if (this.data.typeKey) params.push('type=' + this.data.typeKey)
      if (this.data.statusKey) params.push('status=' + this.data.statusKey)
      if (this.data.keyword.trim()) params.push('q=' + encodeURIComponent(this.data.keyword.trim()))
      const res = await request('/api/questions?' + params.join('&'))
      const items = (res.items || []).map((q) =>
        Object.assign({}, q, {
          typeLabel: TYPE_LABEL[q.type] || q.type,
          pending: q.status === 'NO_ANSWER',
          selected: false
        })
      )
      this.setData({
        questions: replace ? items : this.data.questions.concat(items),
        total: res.total || 0,
        page,
        hasMore: page * this.data.size < (res.total || 0),
        selectedCount: 0
      })
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ loading: false, loadingMore: false })
    }
  },

  loadMore() {
    if (!this.data.hasMore || this.data.loadingMore) return
    this.loadQuestions(this.data.page + 1, false)
  },

  toggleSelect(e) {
    const id = e.currentTarget.dataset.id
    const questions = this.data.questions.map((q) =>
      q.id === id ? Object.assign({}, q, { selected: !q.selected }) : q
    )
    this.setData({
      questions,
      selectedCount: questions.filter((q) => q.selected).length
    })
  },

  openDetail(e) {
    const id = e.currentTarget.dataset.id
    const item = this.data.questions.find((q) => q.id === id)
    if (!item) return
    this.setData({
      detail: item,
      editAnswer: item.answer || '',
      editAnalysis: item.analysis || ''
    })
  },

  closeDetail() {
    this.setData({ detail: null })
  },

  noop() {},

  onAnswer(e) {
    this.setData({ editAnswer: e.detail.value })
  },

  onAnalysis(e) {
    this.setData({ editAnalysis: e.detail.value })
  },

  async saveDetail() {
    if (this.data.saving || !this.data.detail) return
    this.setData({ saving: true })
    try {
      const updated = await request('/api/questions/' + this.data.detail.id, {
        method: 'PATCH',
        data: {
          answer: this.data.editAnswer,
          analysis: this.data.editAnalysis
        }
      })
      const questions = this.data.questions.map((q) =>
        q.id === updated.id
          ? Object.assign({}, q, updated, {
              typeLabel: TYPE_LABEL[updated.type] || updated.type,
              pending: updated.status === 'NO_ANSWER',
              selected: q.selected
            })
          : q
      )
      this.setData({ questions, detail: null })
      wx.showToast({ title: '已保存', icon: 'success' })
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ saving: false })
    }
  },

  deleteDetail() {
    if (!this.data.detail || this.data.deleting) return
    const id = this.data.detail.id
    wx.showModal({
      title: '删除题目',
      content: '删除后不可恢复，确定删除这道题吗？',
      confirmColor: '#dc2626',
      success: async (res) => {
        if (!res.confirm) return
        this.setData({ deleting: true })
        try {
          await request('/api/questions/' + id, { method: 'DELETE' })
          this.setData({
            detail: null,
            questions: this.data.questions.filter((q) => q.id !== id),
            total: Math.max(0, this.data.total - 1)
          })
          wx.showToast({ title: '已删除', icon: 'success' })
        } catch (e) {
          toastError(e)
        } finally {
          this.setData({ deleting: false })
        }
      }
    })
  },

  batchDelete() {
    const ids = this.data.questions.filter((q) => q.selected).map((q) => q.id)
    if (!ids.length) return
    wx.showModal({
      title: '删除所选题目',
      content: '将删除 ' + ids.length + ' 道题，删除后不可恢复。',
      confirmColor: '#dc2626',
      success: async (res) => {
        if (!res.confirm) return
        try {
          await request('/api/questions/delete', {
            method: 'POST',
            data: { ids }
          })
          wx.showToast({ title: '已删除', icon: 'success' })
          this.loadQuestions(1, true)
        } catch (e) {
          toastError(e)
        }
      }
    })
  }
})
