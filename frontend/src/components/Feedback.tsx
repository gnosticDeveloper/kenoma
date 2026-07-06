import { useTranslation } from 'react-i18next'
import type { ApiState } from '../hooks/useApiCall'
import { keyForMessage } from '../api/errors'

interface Props {
  state: ApiState<unknown>
  successLabel?: string
}

export function Feedback({ state, successLabel }: Props) {
  const { t } = useTranslation()
  if (state.status === 'idle' || state.status === 'loading') return null

  if (state.status === 'error') {
    return (
      <div className="feedback feedback-error">
        <span>{t(keyForMessage(state.message))}</span>
        <details>
          <summary>{t('common.actions.details')}</summary>
          <pre>{state.message}</pre>
        </details>
      </div>
    )
  }

  const { data } = state
  if (data == null) {
    return <div className="feedback feedback-success">{successLabel ?? t('common.actions.save')}</div>
  }
  if (typeof data === 'boolean') {
    return <div className={`feedback ${data ? 'feedback-success' : 'feedback-error'}`}>{String(data)}</div>
  }
  return (
    <div className="feedback feedback-success">
      <span>{successLabel ?? t('common.actions.save')}</span>
      <details>
        <summary>{t('common.actions.details')}</summary>
        <pre>{JSON.stringify(data, null, 2)}</pre>
      </details>
    </div>
  )
}
