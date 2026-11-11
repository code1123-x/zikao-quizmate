import { ref } from 'vue'

export const toastText = ref('')
let timer: number | undefined

export function toast(message: string) {
  toastText.value = message
  window.clearTimeout(timer)
  timer = window.setTimeout(() => {
    toastText.value = ''
  }, 1800)
}
