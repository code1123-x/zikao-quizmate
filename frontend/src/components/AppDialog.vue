<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { dialogState, settleDialog } from '../dialog'

const state = dialogState
const text = ref('')
const error = ref('')
const inputEl = ref<HTMLInputElement | null>(null)
const okEl = ref<HTMLButtonElement | null>(null)

const icon = computed(() => {
  const s = state.value
  if (!s) return ''
  if (s.tone === 'danger' || s.tone === 'warn') return '!'
  if (s.kind === 'prompt') return '✎'
  if (s.tone === 'success') return '✓'
  return '?'
})

watch(state, async (s) => {
  error.value = ''
  document.body.style.overflow = s ? 'hidden' : ''
  if (!s) return
  text.value = s.value
  await nextTick()
  if (s.kind === 'prompt') {
    inputEl.value?.focus()
    inputEl.value?.select()
  } else {
    okEl.value?.focus()
  }
})

function cancel() {
  if (!state.value) return
  settleDialog(null)
}

function ok() {
  const s = state.value
  if (!s) return
  if (s.kind === 'prompt') {
    const value = text.value.trim()
    if (s.required && !value) {
      error.value = '这里不能为空'
      inputEl.value?.focus()
      return
    }
    settleDialog(value)
    return
  }
  settleDialog(true)
}

function onKey(e: KeyboardEvent) {
  if (!state.value) return
  if (e.key === 'Escape') {
    e.preventDefault()
    cancel()
  }
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <Transition name="dlg">
      <div v-if="state" class="dlg-mask" @click.self="cancel">
        <div class="dlg-card" :class="state.tone" role="dialog" aria-modal="true">
          <div class="dlg-head">
            <span class="dlg-icon">{{ icon }}</span>
            <div class="dlg-titles">
              <h3>{{ state.title }}</h3>
              <p v-if="state.message">{{ state.message }}</p>
            </div>
          </div>

          <p v-if="state.detail" class="dlg-detail">{{ state.detail }}</p>

          <label v-if="state.kind === 'prompt'" class="dlg-field">
            <span v-if="state.label">{{ state.label }}</span>
            <input
              ref="inputEl"
              v-model="text"
              class="dlg-input"
              :class="{ bad: !!error }"
              :placeholder="state.placeholder"
              @input="error = ''"
              @keyup.enter="ok"
            />
            <em v-if="error" class="dlg-error">{{ error }}</em>
          </label>

          <div class="dlg-actions">
            <button class="dlg-btn ghost" @click="cancel">{{ state.cancelText }}</button>
            <button ref="okEl" class="dlg-btn main" :class="state.tone" @click="ok">
              {{ state.confirmText }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.dlg-mask {
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
.dlg-card {
  width: min(430px, 100%);
  padding: 22px 22px 18px;
  border: 1px solid var(--line);
  border-radius: 20px;
  background: var(--card);
  box-shadow: 0 34px 80px -28px rgba(15, 18, 40, 0.55);
}
.dlg-head {
  display: flex;
  align-items: flex-start;
  gap: 13px;
}
.dlg-icon {
  flex: none;
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: 14px;
  font-size: 17px;
  font-weight: 800;
  line-height: 1;
  color: var(--primary);
  background: var(--primary-soft);
  box-shadow: inset 0 0 0 1px rgba(91, 91, 224, 0.16);
}
.dlg-card.danger .dlg-icon {
  color: var(--bad);
  background: var(--bad-soft);
  box-shadow: inset 0 0 0 1px rgba(220, 38, 38, 0.16);
}
.dlg-card.warn .dlg-icon {
  color: var(--warn);
  background: var(--warn-soft);
  box-shadow: inset 0 0 0 1px rgba(217, 119, 6, 0.18);
}
.dlg-card.success .dlg-icon {
  color: var(--ok);
  background: var(--ok-soft);
  box-shadow: inset 0 0 0 1px rgba(22, 163, 74, 0.16);
}
.dlg-titles {
  min-width: 0;
  padding-top: 1px;
}
.dlg-titles h3 {
  margin: 0 0 4px;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--fg);
}
.dlg-titles p {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  color: var(--muted);
  word-break: break-word;
}
.dlg-detail {
  margin: 14px 0 0;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fafaff;
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
  word-break: break-word;
}
.dlg-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 16px;
}
.dlg-field > span {
  font-size: 11.5px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--muted);
}
.dlg-input {
  height: 44px;
  padding: 0 13px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--card);
  color: var(--fg);
  font-family: inherit;
  font-size: 14px;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.dlg-input::placeholder {
  color: #a4abc2;
}
.dlg-input:focus {
  outline: none;
  border-color: var(--primary);
  box-shadow: var(--ring);
}
.dlg-input.bad {
  border-color: var(--bad);
  box-shadow: 0 0 0 3px rgba(220, 38, 38, 0.14);
}
.dlg-error {
  margin: 0;
  font-style: normal;
  font-size: 11.5px;
  color: var(--bad);
}
.dlg-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 20px;
}
.dlg-btn {
  height: 40px;
  padding: 0 18px;
  border-radius: 12px;
  border: 1px solid transparent;
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 700;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s, background 0.15s, border-color 0.15s;
}
.dlg-btn:active {
  transform: scale(0.98);
}
.dlg-btn:focus-visible {
  outline: none;
  box-shadow: var(--ring);
}
.dlg-btn.ghost {
  border-color: var(--line);
  background: var(--card);
  color: var(--muted);
}
.dlg-btn.ghost:hover {
  border-color: var(--line-strong);
  background: #fafaff;
  color: var(--fg);
}
.dlg-btn.main {
  color: #fff;
  background: linear-gradient(135deg, #6b6bee, #5b5be0);
  box-shadow: 0 12px 24px -14px rgba(91, 91, 224, 0.95);
}
.dlg-btn.main:hover {
  box-shadow: 0 16px 28px -14px rgba(91, 91, 224, 0.95);
}
.dlg-btn.main.danger {
  background: linear-gradient(135deg, #ef5350, #dc2626);
  box-shadow: 0 12px 24px -14px rgba(220, 38, 38, 0.9);
}
.dlg-btn.main.danger:hover {
  box-shadow: 0 16px 28px -14px rgba(220, 38, 38, 0.9);
}
.dlg-btn.main.warn {
  background: linear-gradient(135deg, #f59e0b, #d97706);
  box-shadow: 0 12px 24px -14px rgba(217, 119, 6, 0.9);
}
.dlg-btn.main.warn:hover {
  box-shadow: 0 16px 28px -14px rgba(217, 119, 6, 0.9);
}

.dlg-enter-active,
.dlg-leave-active {
  transition: opacity 0.16s ease;
}
.dlg-enter-active .dlg-card {
  transition: transform 0.2s cubic-bezier(0.2, 0.7, 0.3, 1);
}
.dlg-enter-from,
.dlg-leave-to {
  opacity: 0;
}
.dlg-enter-from .dlg-card {
  transform: translateY(10px) scale(0.975);
}

@media (max-width: 480px) {
  .dlg-mask {
    padding: 14px;
  }
  .dlg-card {
    padding: 20px 18px 16px;
  }
  .dlg-actions {
    flex-direction: column-reverse;
  }
  .dlg-btn {
    width: 100%;
  }
}
</style>
