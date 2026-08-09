import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useApiCall } from '../hooks/useApiCall'
import { Feedback } from '../components/Feedback'

interface Props {
  i18nPrefix: string
  confirm: (orgId: string, token: string) => Promise<void>
}

export default function EmailConfirmPage({ i18nPrefix, confirm: confirmFn }: Props) {
  const { t } = useTranslation()
  const params = new URLSearchParams(window.location.search)
  const orgId = params.get('orgId')
  const token = params.get('token')
  const confirm = useApiCall<void>()

  useEffect(() => {
    if (orgId && token) confirm.call(() => confirmFn(orgId, token))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (!orgId || !token) {
    return (
      <div className="login-wrap">
        <div className="login-card">
          <h1>{t(`${i18nPrefix}.title`)}</h1>
          <div className="error">{t(`${i18nPrefix}.invalidLink`)}</div>
        </div>
      </div>
    )
  }

  return (
    <div className="login-wrap">
      <div className="login-card">
        <h1>{t(`${i18nPrefix}.title`)}</h1>
        {confirm.state.status === 'success' && (
          <div className="success">{t(`${i18nPrefix}.success`)}</div>
        )}
        {confirm.state.status === 'loading' && (
          <div>{t(`${i18nPrefix}.confirming`)}</div>
        )}
        {confirm.state.status === 'error' && <Feedback state={confirm.state} />}
        {confirm.state.status !== 'loading' && (
          <button className="btn btn-primary btn-full" style={{ marginTop: 16 }} onClick={() => { window.location.href = '/' }}>
            {t(`${i18nPrefix}.backToLogin`)}
          </button>
        )}
      </div>
    </div>
  )
}
