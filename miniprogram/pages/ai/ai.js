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
    cfg: null,
    stats: [],
    subjects: [],
    subjectNames: [],
    subjectIndex: 0,
    activeSubjectId: null,
    activeSubjectName: '',
    activeTotal: 0,
    activeUnchecked: 0,
    statuses: [
      { key: 'NEW', label: '待处理' },
      { key: 'APPLIED', label: '已采纳' },
      { key: 'IGNORED', label: '已忽略' }
    ],
    statusFilter: 'NEW',
    checks: [],
    progress: null,
    progressPct: 0,
    starting: false,
    applyingId: null,
    applyingAll: false
  },

  onShow() {
    if (!wx.getStorageSync('quizmate.token')) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.load()
  },

  onHide() {
    this.stopPolling()
  },

  onUnload() {
    this.stopPolling()
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true })
    try {
      const [cfg, subjects, stats] = await Promise.all([
        request('/api/ai/config'),
        request('/api/subjects'),
        request('/api/ai/check-stats')
      ])
      const statList = stats || []
      const subjectList = (subjects || []).filter((s) => s.choiceCount > 0)
      const options = statList
        .filter((st) => st.total > 0)
        .map((st) => {
          const s = subjectList.find((x) => x.id === st.subjectId)
          return {
            subjectId: st.subjectId,
            name: s ? s.name : '学科 ' + st.subjectId,
            total: st.total,
            unchecked: st.unchecked
          }
        })
      let index = options.findIndex((s) => s.unchecked > 0)
      if (index < 0) index = 0
      const active = options[index]
      this.setData(
        {
          cfg,
          stats: options,
          subjects: options.map((s) => ({ id: s.subjectId, name: s.name })),
          subjectNames: options.map((s) => s.name),
          subjectIndex: index,
          activeSubjectId: active ? active.subjectId : null,
          activeSubjectName: active ? active.name : '',
          activeTotal: active ? active.total : 0,
          activeUnchecked: active ? active.unchecked : 0
        },
        () => {
          if (active) this.loadChecks()
          else this.setData({ loading: false })
        }
      )
      const progress = await request('/api/ai/checks/progress')
      this.setData({
        progress,
        progressPct: this.pct(progress)
      })
      if (progress && progress.running) this.startPolling()
    } catch (e) {
      this.setData({ loading: false })
      toastError(e)
    }
  },

  pct(p) {
    if (!p || !p.total) return 0
    return Math.max(0, Math.min(100, Math.round((p.done / p.total) * 100)))
  },

  pickSubject(e) {
    const index = Number(e.detail.value)
    const active = this.data.stats[index]
    if (!active) return
    this.setData(
      {
        subjectIndex: index,
        activeSubjectId: active.subjectId,
        activeSubjectName: active.name,
        activeTotal: active.total,
        activeUnchecked: active.unchecked,
        checks: []
      },
      () => this.loadChecks()
    )
  },

  setStatus(e) {
    const key = e.currentTarget.dataset.key
    if (key === this.data.statusFilter) return
    this.setData({ statusFilter: key }, () => this.loadChecks())
  },

  async loadChecks() {
    if (!this.data.activeSubjectId) {
      this.setData({ loading: false })
      return
    }
    try {
      const list = await request(
        '/api/ai/checks?subjectId=' +
          this.data.activeSubjectId +
          '&status=' +
          this.data.statusFilter +
          '&limit=100'
      )
      this.setData({
        checks: (list || []).map((c) =>
          Object.assign({}, c, { typeLabel: TYPE_LABEL[c.type] || c.type })
        ),
        loading: false
      })
    } catch (e) {
      this.setData({ loading: false })
      toastError(e)
    }
  },

  async start() {
    if (!this.data.activeSubjectId || this.data.starting) return
    if (this.data.cfg && !this.data.cfg.hasKey) {
      wx.showModal({
        title: '还没有配置 AI',
        content: '请先在网页端「设置 → AI 接口」里填写 API Key。',
        showCancel: false
      })
      return
    }
    this.setData({ starting: true })
    try {
      const r = await request('/api/ai/checks/start', {
        method: 'POST',
        data: {
          subjectId: this.data.activeSubjectId,
          limit: 100,
          onlyUnchecked: true
        }
      })
      wx.showToast({ title: r.message || '已开始校验', icon: 'none' })
      if (r.started) this.startPolling()
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ starting: false })
    }
  },

  startPolling() {
    this.stopPolling()
    this.polling = true
    const tick = async () => {
      if (!this.polling) return
      try {
        const p = await request('/api/ai/checks/progress')
        this.setData({ progress: p, progressPct: this.pct(p) })
        if (p && p.running) {
          this.pollTimer = setTimeout(tick, 2000)
        } else {
          this.polling = false
          this.loadChecks()
        }
      } catch (e) {
        this.polling = false
      }
    }
    tick()
  },

  stopPolling() {
    this.polling = false
    if (this.pollTimer) {
      clearTimeout(this.pollTimer)
      this.pollTimer = null
    }
  },

  async apply(e) {
    const id = e.currentTarget.dataset.id
    if (this.data.applyingId) return
    this.setData({ applyingId: id })
    try {
      await request('/api/ai/checks/' + id + '/apply', { method: 'POST' })
      wx.showToast({ title: '已采纳', icon: 'success' })
      this.setData({ checks: this.data.checks.filter((c) => c.questionId !== id) })
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ applyingId: null })
    }
  },

  async ignore(e) {
    const id = e.currentTarget.dataset.id
    try {
      await request('/api/ai/checks/' + id + '/ignore', { method: 'POST' })
      this.setData({ checks: this.data.checks.filter((c) => c.questionId !== id) })
    } catch (e) {
      toastError(e)
    }
  },

  applyAll() {
    if (!this.data.checks.length || this.data.applyingAll) return
    const ids = this.data.checks.map((c) => c.questionId)
    wx.showModal({
      title: '采纳全部 AI 答案',
      content: '把当前列表里的 ' + ids.length + ' 道题改成 AI 给出的答案。',
      success: async (res) => {
        if (!res.confirm) return
        this.setData({ applyingAll: true })
        try {
          const r = await request('/api/ai/checks/apply-all', {
            method: 'POST',
            data: { subjectId: this.data.activeSubjectId, questionIds: ids }
          })
          wx.showToast({ title: r.message || '已采纳', icon: 'none' })
          this.loadChecks()
        } catch (e) {
          toastError(e)
        } finally {
          this.setData({ applyingAll: false })
        }
      }
    })
  },

  webConfig() {
    wx.showModal({
      title: 'AI 接口设置',
      content: '请先在网页端「设置 → AI 接口」里配置 API Key、接口地址和模型。',
      showCancel: false
    })
  }
})
