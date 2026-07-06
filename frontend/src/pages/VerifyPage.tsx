import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { vassago } from '../api/vassago'
import { useApiCall } from '../hooks/useApiCall'
import { Feedback } from '../components/Feedback'

export default function VerifyPage() {
  const { t } = useTranslation()
  const params = new URLSearchParams(window.location.search)
  const orgId = params.get('orgId')
  const token = params.get('token')
  const [newPassword, setNewPassword] = useState('')
  const verify = useApiCall<void>()

  if (!orgId || !token) {
    return (
      <div className="login-wrap">
        <div className="login-card">
          <h1>{t('verifyPage.title')}</h1>
          <div className="error">{t('verifyPage.invalidLink')}</div>
        </div>
      </div>
    )
  }

  return (
    <div className="login-wrap">
      <div className="login-card">
        <h1>{t('verifyPage.title')}</h1>
        {verify.state.status === 'success' ? (
          <>
            <div className="success">{t('verifyPage.success')}</div>
            <button className="btn btn-primary btn-full" style={{ marginTop: 16 }} onClick={() => { window.location.href = '/' }}>
              {t('verifyPage.backToLogin')}
            </button>
          </>
        ) : (
          <>
            <div className="login-fields">
              <div className="field">
                <label>{t('verifyPage.newPassword')}</label>
                <input
                  type="password"
                  value={newPassword}
                  onChange={e => setNewPassword(e.target.value)}
                  autoComplete="new-password"
                />
              </div>
            </div>
            <button
              className="btn btn-primary btn-full"
              disabled={verify.state.status === 'loading' || !newPassword}
              onClick={() => verify.call(() => vassago.users.verify({ orgId, token, newPassword }))}
            >
              {verify.state.status === 'loading' ? t('verifyPage.submitting') : t('verifyPage.submit')}
            </button>
            {verify.state.status === 'error' && <Feedback state={verify.state} />}
          </>
        )}
      </div>
    </div>
  )
}
