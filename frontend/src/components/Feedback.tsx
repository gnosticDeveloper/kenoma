import type { ApiState } from '../hooks/useApiCall'

interface Props {
  state: ApiState<unknown>
  successLabel?: string
}

export function Feedback({ state, successLabel }: Props) {
  if (state.status === 'idle' || state.status === 'loading') return null
  if (state.status === 'error') return <div className="error">{state.message}</div>

  const { data } = state
  if (data == null) return <div className="success">{successLabel ?? '204 No Content'}</div>
  if (typeof data === 'boolean') return <div className={data ? 'success' : 'error'}>{String(data)}</div>
  return <pre className="response">{JSON.stringify(data, null, 2)}</pre>
}
