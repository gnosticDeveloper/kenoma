import { useState } from 'react'
import { raum } from '../api/raum'
import { useApiCall } from '../hooks/useApiCall'
import { Feedback } from '../components/Feedback'
import type { BimePreset, OnboardingRequest } from '../types'

interface Props { token: string }

const PRESETS: { value: BimePreset; label: string }[] = [
  { value: 'CLOTHING_STORE',    label: 'Clothing Store' },
  { value: 'BOOK_STORE',        label: 'Book Store' },
  { value: 'REPAIR_SHOP',       label: 'Repair Shop' },
  { value: 'STORAGE_WAREHOUSE', label: 'Storage Warehouse' },
]

export default function OnboardingPage({ token }: Props) {
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

      <div className="panel">
        <h2>Initiate Onboarding</h2>
        <p className="panel-hint">
          Creates the admin user in Vassago and seeds the initial Bime inventory preset for the given org.
        </p>
        <div className="fields">
          <div className="field">
            <label>Org ID</label>
            <input value={orgId} onChange={e => setOrgId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" />
          </div>
          <div className="field">
            <label>Admin First Name</label>
            <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="Jane" />
          </div>
          <div className="field">
            <label>Admin Last Name</label>
            <input value={form.lastName} onChange={e => setForm(f => ({ ...f, lastName: e.target.value }))} placeholder="Doe" />
          </div>
          <div className="field">
            <label>Admin Email</label>
            <input value={form.email} onChange={e => setForm(f => ({ ...f, email: e.target.value }))} placeholder="jane@acme.com" />
          </div>
          <div className="field">
            <label>Username</label>
            <input value={form.username} onChange={e => setForm(f => ({ ...f, username: e.target.value }))} placeholder="jane_doe" />
          </div>
          <div className="field">
            <label>Inventory Preset</label>
            <select value={form.bimePreset} onChange={e => setForm(f => ({ ...f, bimePreset: e.target.value as BimePreset }))}>
              {PRESETS.map(p => (
                <option key={p.value} value={p.value}>{p.label}</option>
              ))}
            </select>
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={initiate.state.status === 'loading' || !orgId.trim()}
            onClick={() => initiate.call(() => raum.onboarding.initiate(orgId.trim(), form, token))}
          >
            {initiate.state.status === 'loading' ? 'Initiating…' : 'Initiate onboarding'}
          </button>
        </div>
        <Feedback state={initiate.state} successLabel="Onboarding initiated successfully." />
      </div>

    </div>
  )
}
