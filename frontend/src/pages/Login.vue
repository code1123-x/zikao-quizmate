<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'

const hasUsers = ref(true)
const mode = ref<'login' | 'register'>('login')
const username = ref('')
const password = ref('')
const confirm = ref('')
const busy = ref(false)
const error = ref('')
const loading = ref(true)

onMounted(async () => {
  try {
    const s = await api.authStatus()
    hasUsers.value = s.hasUsers
    mode.value = s.hasUsers ? 'login' : 'register'
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
})

function switchMode(next: 'login' | 'register') {
  mode.value = next
  error.value = ''
  confirm.value = ''
}

async function submit() {
  if (busy.value) return
  error.value = ''
  if (!username.value.trim()) {
    error.value = '请输入用户名'
    return
  }
  if (!password.value) {
    error.value = '请输入密码'
    return
  }
  if (mode.value === 'register' && password.value !== confirm.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  busy.value = true
  try {
    if (mode.value === 'register') {
      await api.register(username.value.trim(), password.value)
    } else {
      await api.login(username.value.trim(), password.value)
    }
    window.location.href = '/'
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <span class="logo" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H11v17H6.5A2.5 2.5 0 0 0 4 22V5.5Z"/><path d="M20 5.5A2.5 2.5 0 0 0 17.5 3H13v17h4.5A2.5 2.5 0 0 1 20 22V5.5Z"/><path d="m8 9 1.5 1.5L12 8"/></svg></span>
        <div>
          <b>自考刷题</b>
          <span>每个账号有自己的资料目录、学科和题库</span>
        </div>
      </div>

      <div v-if="loading" class="loading">正在检查登录状态…</div>

      <template v-else>
        <div v-if="!hasUsers" class="first-run">
          这是第一次使用，先创建一个管理员账号。
        </div>

        <div v-if="hasUsers" class="tabs">
          <button class="tab" :class="{ on: mode === 'login' }" @click="switchMode('login')">登录</button>
          <button class="tab" :class="{ on: mode === 'register' }" @click="switchMode('register')">注册</button>
        </div>

        <div class="form">
          <label>
            <span>用户名</span>
            <input
              v-model="username"
              class="inp"
              autocomplete="username"
              placeholder="2–32 位中文 / 字母 / 数字"
              @keyup.enter="submit"
            />
          </label>
          <label>
            <span>密码</span>
            <input
              v-model="password"
              class="inp"
              type="password"
              :autocomplete="mode === 'register' ? 'new-password' : 'current-password'"
              placeholder="至少 6 位"
              @keyup.enter="submit"
            />
          </label>
          <label v-if="mode === 'register'">
            <span>确认密码</span>
            <input
              v-model="confirm"
              class="inp"
              type="password"
              autocomplete="new-password"
              placeholder="再输入一次"
              @keyup.enter="submit"
            />
          </label>
        </div>

        <p v-if="error" class="error">{{ error }}</p>

        <button class="btn submit" :disabled="busy" @click="submit">
          {{ busy ? '请稍候…' : mode === 'register' ? '创建账号并进入' : '登录' }}
        </button>

        <p class="note">
          登录后只能看到自己账号的资料库。已有数据会归到第一个创建的账号。
        </p>
      </template>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(760px 520px at 8% 4%, rgba(99, 91, 206, .22), transparent 65%),
    radial-gradient(680px 520px at 96% 100%, rgba(56, 217, 197, .13), transparent 60%),
    linear-gradient(135deg, #eef0fb 0%, #f8f9fd 52%, #edf5f5 100%),
    var(--bg);
  position: relative;
  overflow: hidden;
}
.login-page::before,
.login-page::after {
  content: '';
  position: absolute;
  border: 1px solid rgba(99, 91, 206, .12);
  border-radius: 50%;
  pointer-events: none;
}
.login-page::before { width: 420px; height: 420px; left: -220px; top: -180px; }
.login-page::after { width: 560px; height: 560px; right: -300px; bottom: -300px; }
.login-card {
  position: relative;
  z-index: 1;
  width: min(430px, 100%);
  padding: 32px;
  border: 1px solid rgba(99, 91, 206, .16);
  border-radius: 24px;
  background: rgba(255, 255, 255, .88);
  box-shadow: 0 34px 90px -38px rgba(31, 28, 91, .42), 0 8px 24px -20px rgba(31, 28, 91, .28);
  backdrop-filter: blur(18px);
}
.brand {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 26px;
  color: var(--fg);
}
.brand .logo {
  width: 46px;
  height: 46px;
  border-radius: 15px;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 18px;
  font-weight: 800;
  background: linear-gradient(135deg, #282650, #635bce 58%, #8b7bff);
  box-shadow: 0 14px 28px -14px rgba(71, 64, 178, .9);
}
.brand .logo svg { width: 23px; height: 23px; stroke: currentColor; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
.brand b {
  display: block;
  font-size: 20px;
  letter-spacing: -.03em;
}
.brand span {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: var(--muted);
}
.loading {
  padding: 30px 0;
  text-align: center;
  color: var(--muted);
  font-size: 13px;
}
.first-run {
  margin-bottom: 16px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--primary-soft);
  color: var(--primary);
  font-size: 12.5px;
}
.tabs {
  display: flex;
  gap: 4px;
  padding: 4px;
  margin-bottom: 20px;
  border-radius: 13px;
  background: #f1f2fa;
}
.tab {
  flex: 1;
  height: 38px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  color: var(--muted);
  font-family: inherit;
  font-weight: 600;
}
.tab.on {
  color: var(--primary);
  border-color: rgba(99, 91, 206, .16);
  background: #fff;
  box-shadow: 0 5px 12px -10px rgba(31, 28, 91, .55);
}
.form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.form label {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.form label > span {
  font-size: 12px;
  color: var(--muted);
}
.inp {
  height: 44px;
  padding: 0 13px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: #fbfbfe;
  color: var(--fg);
  font-family: inherit;
  font-size: 14px;
}
.inp:focus {
  outline: none;
  border-color: var(--primary);
  box-shadow: var(--ring);
}
.error {
  margin: 12px 0 0;
  padding: 9px 11px;
  border-radius: 9px;
  background: var(--bad-soft);
  color: var(--bad);
  font-size: 12.5px;
}
.submit {
  width: 100%;
  margin-top: 18px;
  height: 46px;
  border-radius: 13px;
  background: linear-gradient(135deg, #282650 0%, #635bce 62%, #786cf1 100%);
  box-shadow: 0 16px 28px -16px rgba(71, 64, 178, .85);
}
.note {
  margin: 16px 0 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.7;
}
</style>
