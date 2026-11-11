function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

function examBadge(date) {
  if (!date) return '未排考期'
  const d = new Date(date + 'T00:00:00')
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const days = Math.round((d.getTime() - today.getTime()) / 86400000)
  const label = d.getMonth() + 1 + '/' + d.getDate()
  if (days < 0) return label + ' 已结束'
  if (days === 0) return label + ' 今天'
  return label + ' · ' + days + ' 天'
}

function todayText() {
  const d = new Date()
  const week = '日一二三四五六'[d.getDay()]
  return d.getMonth() + 1 + ' 月 ' + d.getDate() + ' 日 · 周' + week
}

module.exports = { examBadge, todayText, pad }
