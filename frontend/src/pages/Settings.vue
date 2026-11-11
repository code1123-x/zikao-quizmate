<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, type AiConfigView, type AuthUser } from '../api'
import { toast } from '../toast'

const router = useRouter()

const cfg = ref<AiConfigView | null>(null)
const baseUrl = ref('')
const model = ref('')
const apiKey = ref('')
const thinking = ref(false)
const saving = ref(false)
const testing = ref(false)
const me = ref<AuthUser | null>(null)

onMounted(async () => {
  await Promise.all([loadAi(), loadMe()])
})

async function loadMe() {
  try {
    me.value = await api.authMe()
  } catch {
    /* 401 会由全局事件跳转登录 */
  }
}

async function logout() {
  try {
    await api.logout()
  } finally {
    localStorage.removeItem('quizmate.userId')
    window.location.href = '/'
  }
}

async function loadAi() {
  try {
    const c = await api.aiConfig()
    cfg.value = c
    baseUrl.value = c.baseUrl || ''
    model.value = c.model || ''
    thinking.value = !!c.thinking
    if (c.hasKey) apiKey.value = ''
  } catch (e) {
    toast((e as Error).message)
  }
}

async function saveAi(silent = false) {
  saving.value = true
  try {
    const c = await api.saveAiConfig({
      provider: cfg.value?.provider || 'deepseek',
      baseUrl: baseUrl.value.trim(),
      model: model.value.trim(),
      apiKey: apiKey.value.trim() || undefined,
      thinking: thinking.value
    })
    cfg.value = c
    apiKey.value = ''
    if (!silent) toast('AI 配置已保存')
    return c
  } finally {
    saving.value = false
  }
}

async function saveAiClick() {
  try {
    await saveAi(false)
  } catch (e) {
    toast((e as Error).message)
  }
}

async function testAi() {
  testing.value = true
  try {
    await saveAi(true)
    const r = await api.testAi()
    toast(r.ok ? `连接成功：${r.sample ?? ''}` : r.message)
  } catch (e) {
    toast((e as Error).message)
  } finally {
    testing.value = false
  }
}
</script>

<template>
  <div class="page settings-page">
    <div class="page-head">
      <div>
        <h1 class="hello">设置</h1>
        <p class="sub">管理当前账号和 AI 接口</p>
      </div>
      <button class="btn ghost" @click="router.push('/')">返回首页</button>
    </div>

    <div class="sec-title"><span>当前账号</span></div>
    <div class="card account-row">
      <div>
        <b>{{ me?.username ?? '未登录' }}</b>
        <span>{{ me?.admin ? '管理员账号' : '普通账号' }} · 只能访问自己的资料目录和题库</span>
      </div>
      <button class="btn ghost sm" @click="logout">退出登录</button>
    </div>

    <div class="sec-title"><span>AI 接口</span></div>
    <div class="card">
      <div class="ai-status" :class="cfg?.hasKey ? 'ok' : 'off'">
        {{ cfg?.hasKey ? `已配置：${cfg.model}` : '未配置 API Key' }}
      </div>
      <div class="form config-form">
        <label class="field">
          <span>接口地址</span>
          <input v-model="baseUrl" class="inp" placeholder="https://api.deepseek.com/v1" />
        </label>
        <label class="field">
          <span>模型</span>
          <input v-model="model" class="inp" placeholder="deepseek-flash" />
        </label>
        <label class="field">
          <span>API Key{{ cfg?.apiKeyMasked ? '（已保存，留空不变）' : '' }}</span>
          <input
            v-model="apiKey"
            class="inp"
            type="password"
            :placeholder="cfg?.apiKeyMasked || 'sk-...'"
            autocomplete="off"
          />
        </label>
      </div>
      <label class="think-toggle">
        <input v-model="thinking" type="checkbox" />
        <span>
          <b>深度思考</b>
          校验选择题建议关闭，更快更省
        </span>
      </label>
      <p class="hint">
        还没有 Key？<a href="https://platform.deepseek.com/api_keys" target="_blank" rel="noreferrer">去 DeepSeek 创建</a>。
        保存后可以直接测试连接。
      </p>
      <div class="card-actions">
        <button class="btn" :disabled="saving" @click="saveAiClick">
          {{ saving ? '保存中…' : '保存 AI 配置' }}
        </button>
        <button class="btn ghost" :disabled="testing" @click="testAi">
          {{ testing ? '测试中…' : '测试连接' }}
        </button>
      </div>
    </div>

  </div>
</template>

<style scoped>
.settings-page {
  max-width: 900px;
}
.account-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
}
.account-row b {
  display: block;
  font-size: 14px;
}
.account-row span {
  display: block;
  margin-top: 3px;
  font-size: 12.5px;
  color: var(--muted);
}
.inp {
  height: 40px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--card);
  color: var(--fg);
  font-family: inherit;
  font-size: 14px;
}
.inp:focus {
  outline: none;
  border-color: var(--primary);
  box-shadow: var(--ring);
}
.config-form {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 14px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  flex: 1;
  min-width: 210px;
}
.field > span {
  font-size: 12px;
  color: var(--muted);
}
.ai-status {
  display: inline-flex;
  padding: 5px 11px;
  border-radius: 999px;
  font-size: 12.5px;
  font-weight: 600;
}
.ai-status.ok {
  color: var(--ok);
  background: var(--ok-soft);
}
.ai-status.off {
  color: var(--warn);
  background: var(--warn-soft);
}
.think-toggle {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  margin-top: 14px;
  font-size: 12.5px;
  color: var(--muted);
}
.think-toggle b {
  color: var(--fg);
  margin-right: 8px;
}
.hint {
  margin: 12px 0 0;
  font-size: 12.5px;
  color: var(--muted);
  line-height: 1.7;
}
.hint a {
  color: var(--primary);
}
.card-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 16px;
}
</style>
