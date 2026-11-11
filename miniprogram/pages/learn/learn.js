const { request, toastError } = require('../../utils/request')

const TYPE_LABEL = {
  FILL: '填空',
  TERM: '名词解释',
  SHORT: '简答',
  CALC: '计算',
  ESSAY: '论述'
}

Page({
  data: {
    loading: true,
    subjectName: '知识点',
    cards: [],
    index: 0,
    total: 0,
    progress: 0,
    current: null,
    typeLabel: '',
    revealed: false,
    done: false,
    busy: false,
    stats: { good: 0, vague: 0, bad: 0 }
  },

  onLoad(query) {
    this.subjectId = Number(query.subjectId)
    this.load()
  },

  async load() {
    this.setData({ loading: true })
    try {
      const [summary, cards] = await Promise.all([
        request('/api/cards/summary'),
        request('/api/cards?subjectId=' + this.subjectId + '&limit=50')
      ])
      const subject = (summary || []).find((s) => s.subjectId === this.subjectId)
      this.setData(
        {
          subjectName: subject ? subject.name : '知识点',
          cards: cards || [],
          total: (cards || []).length,
          index: 0,
          progress: 0,
          revealed: false,
          done: false,
          stats: { good: 0, vague: 0, bad: 0 }
        },
        () => this.build()
      )
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ loading: false })
    }
  },

  build() {
    const card = this.data.cards[this.data.index]
    this.setData({
      current: card || null,
      typeLabel: card ? TYPE_LABEL[card.type] || card.type : '',
      revealed: false,
      progress: this.data.total
        ? Math.round((this.data.index / this.data.total) * 100)
        : 0
    })
  },

  reveal() {
    if (!this.data.done) this.setData({ revealed: true })
  },

  async rate(e) {
    if (this.data.busy) return
    const rating = e.currentTarget.dataset.rating
    const card = this.data.current
    if (!card) return
    this.setData({ busy: true })
    try {
      await request('/api/cards/' + card.id + '/review', {
        method: 'POST',
        data: { rating }
      })
      const stats = Object.assign({}, this.data.stats)
      if (rating === 'MASTER') stats.good++
      else if (rating === 'VAGUE') stats.vague++
      else stats.bad++
      if (this.data.index >= this.data.total - 1) {
        this.setData({ done: true, stats, progress: 100 })
      } else {
        this.setData({ index: this.data.index + 1, stats }, () => this.build())
      }
    } catch (e) {
      toastError(e)
    } finally {
      this.setData({ busy: false })
    }
  },

  restart() {
    this.load()
  },

  quit() {
    wx.navigateBack()
  }
})
