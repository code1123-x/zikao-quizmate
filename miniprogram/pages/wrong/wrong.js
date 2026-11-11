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
    summary: [],
    activeId: null,
    activeName: '',
    activeCount: 0,
    items: [],
    total: 0,
    page: 1,
    size: 20,
    hasMore: false,
    loadingMore: false,
    detail: null,
    starting: false
  },

  onShow() {
    if (!wx.getStorageSync('quizmate.token')) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadSummary()
  },

  onPullDownRefresh() {
    this.loadSummary().finally(() => wx.stopPullDownRefresh())
  },

  async loadSummary() {
    this.setData({ loading: true })
    try {
      const summary = await request('/api/wrong/summary')
      const list = summary || []
      let activeId = this.data.activeId
      if (!activeId && list.length) activeId = list[0].subjectId
      const active = list.find((s) => s.subjectId === activeId)
      this.setData({
        summary: list,
        activeId: active ? active.subjectId : null,
        activeName: active ? active.name : '',
        activeCount: active ? active.count : 0
      })
      if (active) {
        await this.loadItems(1, true)
      } else {
        this.setData({ items: [], total: 0, hasMore: false })
      }
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ loading: false })
    }
  },

  pickSubject(e) {
    const id = Number(e.currentTarget.dataset.id)
    if (id === this.data.activeId) return
    const active = this.data.summary.find((s) => s.subjectId === id)
    this.setData(
      {
        activeId: id,
        activeName: active ? active.name : '',
        activeCount: active ? active.count : 0,
        items: [],
        total: 0,
        page: 1,
        hasMore: false
      },
      () => this.loadItems(1, true)
    )
  },

  async loadItems(page, replace) {
    if (!this.data.activeId) return
    if (!replace) this.setData({ loadingMore: true })
    try {
      const res = await request(
        '/api/wrong?subjectId=' +
          this.data.activeId +
          '&page=' +
          page +
          '&size=' +
          this.data.size
      )
      const items = (res.items || []).map((q) =>
        Object.assign({}, q, { typeLabel: TYPE_LABEL[q.type] || q.type })
      )
      this.setData({
        items: replace ? items : this.data.items.concat(items),
        total: res.total || 0,
        page,
        hasMore: page * this.data.size < (res.total || 0)
      })
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ loadingMore: false })
    }
  },

  loadMore() {
    if (!this.data.hasMore || this.data.loadingMore) return
    this.loadItems(this.data.page + 1, false)
  },

  openDetail(e) {
    const id = e.currentTarget.dataset.id
    const item = this.data.items.find((q) => q.id === id)
    if (item) this.setData({ detail: item })
  },

  closeDetail() {
    this.setData({ detail: null })
  },

  noop() {},

  async practiceWrong() {
    if (!this.data.activeId || this.data.starting) return
    this.setData({ starting: true })
    try {
      const session = await request('/api/practice/sessions', {
        method: 'POST',
        data: {
          subjectId: this.data.activeId,
          mode: 'RANDOM',
          count: this.data.size,
          onlyWrong: true,
          shuffle: true
        }
      })
      wx.navigateTo({
        url: '/pages/practice/practice?sessionId=' + session.id
      })
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ starting: false })
    }
  }
})
