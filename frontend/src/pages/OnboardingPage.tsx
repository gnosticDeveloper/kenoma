import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { raum } from '../api/raum'
import { useApiCall } from '../hooks/useApiCall'
import { Feedback } from '../components/Feedback'
import { Combobox } from '../components/Combobox'
import type { BimePreset, OnboardingRequest, OrgResponse } from '../types'

interface Props { token: string }

const PRESETS: BimePreset[] = ['CLOTHING_STORE', 'BOOK_STORE', 'REPAIR_SHOP', 'STORAGE_WAREHOUSE']

export default function OnboardingPage({ token }: Props) {
  const { t, i18n } = useTranslation()
  const [orgs, setOrgs] = useState<OrgResponse[]>([])
  useEffect(() => { raum.orgs.list(token).then(setOrgs).catch(() => {}) }, [token])
  const orgItems = orgs.map(o => ({ id: o.id, label: o.name, sublabel: o.contactEmail }))

  const initiate = useApiCall<void>()
  const [orgId, setOrgId] = useState('')
  const [form, setForm] = useState<OnboardingRequest>({
    name: '',
    lastName: '',
    email: '',
    username: '',
    bimePreset: 'CLOTHING_STORE',
  })

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('onboardingPage.title')}</h1>
          <p>{t('onboardingPage.subtitle')}</p>
        </div>
      </div>

      <div className="panel">
        <h2>{t('onboardingPage.panelTitle')}</h2>
        <p className="panel-hint">{t('onboardingPage.hint')}</p>
        <div className="fields">
          <div className="field">
            <label>{t('onboardingPage.org')}</label>
            <Combobox items={orgItems} value={orgId || null} onChange={id => setOrgId(id ?? '')} placeholder={t('onboardingPage.orgPlaceholder')} />
          </div>
          <div className="field">
            <label>{t('onboardingPage.firstName')}</label>
            <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="Jane" />
          </div>
          <div className="field">
            <label>{t('onboardingPage.lastName')}</label>
            <input value={form.lastName} onChange={e => setForm(f => ({ ...f, lastName: e.target.value }))} placeholder="Doe" />
          </div>
          <div className="field">
            <label>{t('onboardingPage.email')}</label>
            <input value={form.email} onChange={e => setForm(f => ({ ...f, email: e.target.value }))} placeholder="jane@acme.com" />
          </div>
          <div className="field">
            <label>{t('onboardingPage.username')}</label>
            <input value={form.username} onChange={e => setForm(f => ({ ...f, username: e.target.value }))} placeholder="jane_doe" />
          </div>
          <div className="field">
            <label>{t('onboardingPage.preset')}</label>
            <select value={form.bimePreset} onChange={e => setForm(f => ({ ...f, bimePreset: e.target.value as BimePreset }))}>
              {PRESETS.map(p => (
                <option key={p} value={p}>{t(`onboardingPage.presets.${p}`)}</option>
              ))}
            </select>
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={initiate.state.status === 'loading' || !orgId}
            onClick={() => initiate.call(() => raum.onboarding.initiate(orgId, { ...form, locale: i18n.language }, token))}
          >
            {initiate.state.status === 'loading' ? t('onboardingPage.submitting') : t('onboardingPage.submit')}
          </button>
        </div>
        <Feedback state={initiate.state} successLabel={t('onboardingPage.success')} />
      </div>
    </div>
  )
}
