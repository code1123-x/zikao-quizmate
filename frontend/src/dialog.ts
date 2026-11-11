import { ref } from 'vue'

export type DialogTone = 'default' | 'danger' | 'warn' | 'success'

export interface DialogOptions {
  title?: string
  message?: string
  detail?: string
  confirmText?: string
  cancelText?: string
  tone?: DialogTone
}

export interface PromptOptions extends DialogOptions {
  label?: string
  placeholder?: string
  value?: string
  required?: boolean
}

export interface DialogState {
  kind: 'confirm' | 'prompt'
  title: string
  message: string
  detail: string
  confirmText: string
  cancelText: string
  tone: DialogTone
  label: string
  placeholder: string
  value: string
  required: boolean
}

let resolveFn: ((value: boolean | string | null) => void) | null = null

/** 全局唯一的弹框状态，由 AppDialog 渲染 */
export const dialogState = ref<DialogState | null>(null)

function open(state: DialogState, resolve: (value: boolean | string | null) => void) {
  // 极端情况下上一个弹框还没关，直接按取消处理
  if (resolveFn) {
    const prev = resolveFn
    resolveFn = null
    prev(null)
  }
  resolveFn = resolve
  dialogState.value = state
}

function normalize(input: string | DialogOptions): DialogOptions {
  return typeof input === 'string' ? { message: input } : input
}

/** 二次确认，返回 true 表示点了确认 */
export function confirmDialog(input: string | DialogOptions): Promise<boolean> {
  const o = normalize(input)
  return new Promise((resolve) => {
    open({
      kind: 'confirm',
      title: o.title || '请确认',
      message: o.message || '',
      detail: o.detail || '',
      confirmText: o.confirmText || '确定',
      cancelText: o.cancelText || '取消',
      tone: o.tone || 'default',
      label: '',
      placeholder: '',
      value: '',
      required: false
    }, (v) => resolve(v === true))
  })
}

/** 输入框弹框，返回输入内容；取消返回 null */
export function promptDialog(input: string | PromptOptions): Promise<string | null> {
  const o = typeof input === 'string' ? { message: input } : input
  return new Promise((resolve) => {
    open({
      kind: 'prompt',
      title: o.title || '请输入',
      message: o.message || '',
      detail: o.detail || '',
      confirmText: o.confirmText || '确定',
      cancelText: o.cancelText || '取消',
      tone: o.tone || 'default',
      label: o.label || '',
      placeholder: o.placeholder || '',
      value: o.value || '',
      required: o.required !== false
    }, (v) => resolve(typeof v === 'string' ? v : null))
  })
}

/** 关闭当前弹框并返回结果 */
export function settleDialog(value: boolean | string | null) {
  const fn = resolveFn
  resolveFn = null
  dialogState.value = null
  fn?.(value)
}
