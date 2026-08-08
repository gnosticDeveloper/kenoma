import { useState } from 'react'

export type ApiState<T> =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'success'; data: T }
  | { status: 'error'; message: string }

export function useApiCall<T>() {
  const [state, setState] = useState<ApiState<T>>({ status: 'idle' })

  async function call(fn: () => Promise<T>): Promise<{ ok: true } | { ok: false; message: string }> {
    setState({ status: 'loading' })
    try {
      const data = await fn()
      setState({ status: 'success', data })
      return { ok: true }
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e)
      setState({ status: 'error', message })
      return { ok: false, message }
    }
  }

  return { state, call }
}
