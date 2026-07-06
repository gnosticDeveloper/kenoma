import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { vassago } from '../api/vassago'
import { useApiCall } from '../hooks/useApiCall'
import { Feedback } from '../components/Feedback'

interface Props {
  onBack: () => void
}

export default function RecoverPage({ onBack }: Props) {
  const { t } = useTranslation()
  const [orgId, setOrgId] = useState('')
  const [username, setUsername] = useState('')
  const recover = useApiCall<void>()

  function submit() {
    recover.call(() => vassago.recover({ orgId: orgId.trim(), username: username.trim() }))
  }

  return (
    <div className="login-wrap">
      <div className="login-card">
        <h1>{t('recoverPage.title')}</h1>
        <p>{t('recoverPage.hint')}</p>
        <div className="login-fields">
          <div className="field">
            <label>{t('recoverPage.orgId')}</label>
            <input value={orgId} onChange={e => setOrgId(e.target.value)} autoComplete="off" spellCheck={false} />
          </div>
          <div className="field">
            <label>{t('recoverPage.username')}</label>
            <input value={username} onChange={e => setUsername(e.target.value)} autoComplete="username" />
          </div>
        </div>
        <button
          className="btn btn-primary btn-full"
          disabled={recover.state.status === 'loading' || !orgId.trim() || !username.trim()}
          onClick={submit}
        >
          {recover.state.status === 'loading' ? t('recoverPage.submitting') : t('recoverPage.submit')}
        </button>
        {recover.state.status === 'success' && <div className="success">{t('recoverPage.success')}</div>}
        {recover.state.status === 'error' && <Feedback state={recover.state} />}
        <div className="login-links">
          <button className="link-btn" onClick={onBack} type="button">{t('login.backToLogin')}</button>
        </div>
      </div>
    </div>
  )
}
