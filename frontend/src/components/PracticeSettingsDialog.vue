<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { PracticeSettings } from '../settings'

const props = defineProps<PracticeSettings>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'save', v: PracticeSettings): void }>()

const count = ref(props.count)
const goal = ref(props.goal)
const error = ref('')

const countOptions = [10, 20, 30, 50]
const goalOptions = [10, 20, 30, 50, 100]

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
  if (e.key === 'Enter') save()
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

function save() {
  const c = Number(count.value)
  const g = Number(goal.value)
  if (!Number.isFinite(c) || c < 1 || c > 200) {
    error.value = '每次题量请填 1–200 之间的数字'
    return
  }
  if (!Number.isFinite(g) || g < 1 || g > 200) {
    error.value = '今日目标请填 1–200 之间的数字'
    return
  }
  emit('save', { count: Math.round(c), goal: Math.round(g) })
}
</script>

<template>
  <div class="ps-mask" @click.self="emit('close')">
    <div class="ps-card" role="dialog" aria-modal="true" aria-label="练习设置">
      <div class="ps-head">
        <span class="ps-icon">⚙</span>
        <div>
          <h3>练习设置</h3>
        </div>
        <button class="ps-x" title="关闭" @click="emit('close')">✕</button>
      </div>

      <div class="ps-body">
        <div class="ps-group">
          <div class="ps-label">
            <b>每次练选择题</b>
          </div>
          <div class="ps-chips">
            <button
              v-for="n in countOptions"
              :key="n"
              class="ps-chip"
              :class="{ on: count === n }"
              @click="count = n"
            >
              {{ n }} 题
            </button>
          </div>
          <label class="ps-input-row">
            <span>自定义</span>
            <input v-model.number="count" class="ps-inp" type="number" min="1" max="200" />
            <em>题</em>
          </label>
        </div>

        <div class="ps-group">
          <div class="ps-label">
            <b>今日目标</b>
          </div>
          <div class="ps-chips">
            <button
              v-for="n in goalOptions"
              :key="n"
              class="ps-chip"
              :class="{ on: goal === n }"
              @click="goal = n"
            >
              {{ n }} 题
            </button>
          </div>
          <label class="ps-input-row">
            <span>自定义</span>
            <input v-model.number="goal" class="ps-inp" type="number" min="1" max="200" />
            <em>题</em>
          </label>
        </div>

        <p v-if="error" class="ps-error">{{ error }}</p>
        <p class="ps-note">
          每次题量用于首页「练选择题」；组卷和模拟考试会单独设置题量。设置只保存在当前浏览器。
        </p>
      </div>

      <div class="ps-actions">
        <button class="btn ghost" @click="emit('close')">取消</button>
        <button class="btn" @click="save">保存设置</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ps-mask {
  position: fixed;
  inset: 0;
  z-index: 90;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(15, 18, 40, 0.42);
  backdrop-filter: blur(4px);
}
.ps-card {
  width: min(440px, 100%);
  padding: 22px 22px 18px;
  border: 1px solid var(--line);
  border-radius: 20px;
  background: var(--card);
  box-shadow: 0 34px 80px -28px rgba(15, 18, 40, 0.55);
  animation: ps-pop 0.18s cubic-bezier(0.2, 0.7, 0.3, 1) both;
}
.ps-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}
.ps-icon {
  flex: none;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 13px;
  color: var(--primary);
  background: var(--primary-soft);
  box-shadow: inset 0 0 0 1px rgba(91, 91, 224, 0.16);
  font-size: 17px;
}
.ps-head h3 {
  margin: 0;
  font-size: 17px;
  letter-spacing: -0.01em;
}
.ps-x {
  flex: none;
  margin-left: auto;
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.ps-x:hover {
  background: var(--bg);
  color: var(--fg);
}
.ps-body {
  padding-top: 16px;
}
.ps-group + .ps-group {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed var(--line);
}
.ps-label b {
  display: block;
  font-size: 13.5px;
}
.ps-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 10px;
}
.ps-chip {
  height: 32px;
  padding: 0 13px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--card);
  color: var(--muted);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: 0.15s;
}
.ps-chip:hover {
  border-color: var(--primary);
  color: var(--primary);
  background: var(--primary-soft);
}
.ps-chip.on {
  border-color: var(--primary);
  color: var(--primary);
  background: var(--primary-soft);
  box-shadow: 0 0 0 3px rgba(91, 91, 224, 0.12);
}
.ps-input-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
.ps-input-row > span {
  font-size: 12px;
  color: var(--muted);
}
.ps-inp {
  width: 88px;
  height: 36px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--card);
  color: var(--fg);
  font-family: inherit;
  font-size: 13.5px;
}
.ps-inp:focus {
  outline: none;
  border-color: var(--primary);
  box-shadow: var(--ring);
}
.ps-input-row em {
  font-style: normal;
  font-size: 12px;
  color: var(--muted);
}
.ps-error {
  margin: 12px 0 0;
  padding: 8px 11px;
  border-radius: 9px;
  background: var(--bad-soft);
  color: var(--bad);
  font-size: 12px;
}
.ps-note {
  margin: 14px 0 0;
  padding-top: 12px;
  border-top: 1px dashed var(--line);
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.7;
}
.ps-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
}
@keyframes ps-pop {
  from {
    opacity: 0;
    transform: translateY(10px) scale(0.975);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
@media (max-width: 480px) {
  .ps-mask {
    padding: 14px;
  }
  .ps-card {
    padding: 20px 18px 16px;
  }
  .ps-actions {
    flex-direction: column-reverse;
  }
  .ps-actions .btn {
    width: 100%;
  }
}
</style>
