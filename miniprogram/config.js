// 开发时用本机后端；上线前改成你的 HTTPS 域名
const DEV = true

module.exports = {
  // 开发者工具和真机都通过电脑局域网地址访问后端。
  API_BASE: DEV ? 'http://192.168.3.11:8080' : 'https://quiz.example.com'
}
