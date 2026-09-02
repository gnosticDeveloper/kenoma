import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { raum } from '../api/raum'
import { useApiCall } from '../hooks/useApiCall'
import { Feedback } from '../components/Feedback'
import { Combobox } from '../components/Combobox'
import type { BasicCredential, Credentials, OrgResponse, ServiceResponse } from '../types'

interface Props { token: string }

export default function CredentialsPage({ token }: Props) {
  const { t } = useTranslation()
  const [orgs, setOrgs] = useState<OrgResponse[]>([])
  const [services, setServices] = useState<ServiceResponse[]>([])

  useEffect(() => {
    raum.orgs.list(token).then(setOrgs).catch(() => {})
    // Raum manages credentials for other services but never registers its own — exclude it.
    raum.services.list(token).then(list => setServices(list.filter(s => s.name !== 'Raum'))).catch(() => {})
  }, [token])

  const orgItems = orgs.map(o => ({ id: o.id, label: o.name, sublabel: o.contactEmail }))
  const serviceItems = services.map(s => ({ id: s.id, label: s.name, sublabel: s.description }))

  const register = useApiCall<BasicCredential>()
  const [regForm, setRegForm] = useState<Credentials>({
    orgId: '',
    serviceId: '',
    userName: '',
    password: '',
    dbHost: '',
    dbPort: 5432,
    dbName: '',
    dbEngine: 'postgresql',
  })

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('credentialsPage.title')}</h1>
          <p>{t('credentialsPage.subtitle')}</p>
        </div>
      </div>

      <div className="panel">
        <h2>{t('credentialsPage.registerTitle')}</h2>
        <div className="fields">
          <div className="field">
            <label>{t('credentialsPage.org')}</label>
            <Combobox
              items={orgItems}
              value={regForm.orgId || null}
              onChange={id => setRegForm(f => ({ ...f, orgId: id ?? '' }))}
              placeholder={t('credentialsPage.orgPlaceholder')}
            />
          </div>
          <div className="field">
            <label>{t('credentialsPage.service')}</label>
            <Combobox
              items={serviceItems}
              value={regForm.serviceId || null}
              onChange={id => setRegForm(f => ({ ...f, serviceId: id ?? '' }))}
              placeholder={t('credentialsPage.servicePlaceholder')}
            />
          </div>
          <div className="field">
            <label>{t('credentialsPage.username')}</label>
            <input value={regForm.userName} onChange={e => setRegForm(f => ({ ...f, userName: e.target.value }))} placeholder="db_admin" />
          </div>
          <div className="field">
            <label>{t('credentialsPage.password')}</label>
            <input type="password" value={regForm.password} onChange={e => setRegForm(f => ({ ...f, password: e.target.value }))} autoComplete="new-password" />
          </div>
          <div className="field">
            <label>{t('credentialsPage.dbHost')}</label>
            <input value={regForm.dbHost} onChange={e => setRegForm(f => ({ ...f, dbHost: e.target.value }))} placeholder="localhost" />
          </div>
          <div className="field">
            <label>{t('credentialsPage.dbPort')}</label>
            <input type="number" value={regForm.dbPort} onChange={e => setRegForm(f => ({ ...f, dbPort: parseInt(e.target.value, 10) || 0 }))} />
          </div>
          <div className="field">
            <label>{t('credentialsPage.dbName')}</label>
            <input value={regForm.dbName} onChange={e => setRegForm(f => ({ ...f, dbName: e.target.value }))} placeholder="mydb" />
          </div>
          <div className="field">
            <label>{t('credentialsPage.dbEngine')}</label>
            <input value={regForm.dbEngine} onChange={e => setRegForm(f => ({ ...f, dbEngine: e.target.value }))} placeholder="postgresql" />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={register.state.status === 'loading' || !regForm.serviceId || !regForm.orgId}
            onClick={() => register.call(() => raum.credentials.register(regForm, token))}
          >
            {register.state.status === 'loading' ? t('credentialsPage.registering') : t('credentialsPage.register')}
          </button>
        </div>
        <Feedback state={register.state} successLabel={t('credentialsPage.registered')} />
      </div>
    </div>
  )
}
