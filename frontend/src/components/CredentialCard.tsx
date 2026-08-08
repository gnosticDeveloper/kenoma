import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Credentials } from '../types'
import { CopyButton } from './CopyButton'

export function CredentialCard({ cred }: { cred: Credentials }) {
  const { t } = useTranslation()
  const [showPassword, setShowPassword] = useState(false)

  return (
    <div className="entity-card cred-card">
      {(cred.dbHost || cred.dbName || cred.dbEngine) && (
        <div className="cred-section">
          {cred.dbHost && (
            <div className="field-row">
              <span className="field-label">{t('credentialCard.host')}</span>
              <span className="field-value">{cred.dbHost}{cred.dbPort ? `:${cred.dbPort}` : ''}</span>
            </div>
          )}
          {cred.dbName && (
            <div className="field-row">
              <span className="field-label">{t('credentialCard.database')}</span>
              <span className="field-value">{cred.dbName}</span>
            </div>
          )}
          {cred.dbEngine && (
            <div className="field-row">
              <span className="field-label">{t('credentialCard.engine')}</span>
              <span className="field-value">{cred.dbEngine}</span>
            </div>
          )}
          <hr className="cred-divider" />
        </div>
      )}

      <div className="field-row">
        <span className="field-label">{t('credentialCard.username')}</span>
        <span className="field-value mono">{cred.userName}</span>
        <CopyButton text={cred.userName} />
      </div>

      <div className="field-row">
        <span className="field-label">{t('credentialCard.password')}</span>
        <span className="field-value mono">{showPassword ? cred.password : '•'.repeat(20)}</span>
        <button className="copy-btn" onClick={() => setShowPassword(s => !s)}>
          {showPassword ? t('common.actions.hide') : t('common.actions.show')}
        </button>
        <CopyButton text={cred.password} />
      </div>

      {cred.leaseId && (
        <>
          <hr className="cred-divider" />
          <div className="field-row">
            <span className="field-label">{t('credentialCard.leaseId')}</span>
            <span className="field-value mono lease-id">{cred.leaseId}</span>
            <CopyButton text={cred.leaseId} />
          </div>
          {(cred.leaseDuration != null && cred.leaseDuration > 0) && (
            <div className="field-row">
              <span className="field-label">{t('credentialCard.expiresIn')}</span>
              <span className="field-value">{cred.leaseDuration}s</span>
            </div>
          )}
        </>
      )}
    </div>
  )
}
