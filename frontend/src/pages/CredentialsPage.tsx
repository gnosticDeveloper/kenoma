import { useEffect, useState } from 'react'
import { raum } from '../api/raum'
import { useApiCall } from '../hooks/useApiCall'
import { Feedback } from '../components/Feedback'
import { CredentialCard } from '../components/CredentialCard'
import type { BasicCredential, Credentials, ServiceResponse } from '../types'

interface Props { token: string }

export default function CredentialsPage({ token }: Props) {
  const [services, setServices] = useState<ServiceResponse[]>([])

  useEffect(() => {
    // Raum manages credentials for other services but never registers its own — exclude it.
    raum.services.list(token).then(list => setServices(list.filter(s => s.name !== 'Raum'))).catch(() => {})
  }, [token])

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

  const ephemeral = useApiCall<Credentials>()
  const [ephForm, setEphForm] = useState<BasicCredential>({ orgId: '', serviceId: '' })

  const testDb = useApiCall<boolean>()

  return (
    <div className="page">

      <div className="panel">
        <h2>Register Credentials</h2>
        <div className="fields">
          <div className="field">
            <label>Org ID</label>
            <input value={regForm.orgId} onChange={e => setRegForm(f => ({ ...f, orgId: e.target.value }))} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" />
          </div>
          <div className="field">
            <label>Service</label>
            <select
              value={regForm.serviceId}
              onChange={e => setRegForm(f => ({ ...f, serviceId: e.target.value }))}
            >
              <option value="">Select a service…</option>
              {services.map(s => (
                <option key={s.id} value={s.id}>{s.name}</option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>Username</label>
            <input value={regForm.userName} onChange={e => setRegForm(f => ({ ...f, userName: e.target.value }))} placeholder="db_admin" />
          </div>
          <div className="field">
            <label>Password</label>
            <input type="password" value={regForm.password} onChange={e => setRegForm(f => ({ ...f, password: e.target.value }))} autoComplete="new-password" />
          </div>
          <div className="field">
            <label>DB Host</label>
            <input value={regForm.dbHost} onChange={e => setRegForm(f => ({ ...f, dbHost: e.target.value }))} placeholder="localhost" />
          </div>
          <div className="field">
            <label>DB Port</label>
            <input type="number" value={regForm.dbPort} onChange={e => setRegForm(f => ({ ...f, dbPort: parseInt(e.target.value, 10) || 0 }))} />
          </div>
          <div className="field">
            <label>DB Name</label>
            <input value={regForm.dbName} onChange={e => setRegForm(f => ({ ...f, dbName: e.target.value }))} placeholder="mydb" />
          </div>
          <div className="field">
            <label>DB Engine</label>
            <input value={regForm.dbEngine} onChange={e => setRegForm(f => ({ ...f, dbEngine: e.target.value }))} placeholder="postgresql" />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={register.state.status === 'loading' || !regForm.serviceId}
            onClick={() => register.call(() => raum.credentials.register(regForm, token))}
          >
            {register.state.status === 'loading' ? 'Registering…' : 'Register'}
          </button>
        </div>
        <Feedback state={register.state} successLabel="Credentials registered." />
      </div>

      <div className="panel">
        <h2>Issue Ephemeral Credentials</h2>
        <div className="fields">
          <div className="field">
            <label>Org ID</label>
            <input value={ephForm.orgId} onChange={e => setEphForm(f => ({ ...f, orgId: e.target.value }))} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" />
          </div>
          <div className="field">
            <label>Service</label>
            <select
              value={ephForm.serviceId}
              onChange={e => setEphForm(f => ({ ...f, serviceId: e.target.value }))}
            >
              <option value="">Select a service…</option>
              {services.map(s => (
                <option key={s.id} value={s.id}>{s.name}</option>
              ))}
            </select>
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={ephemeral.state.status === 'loading' || !ephForm.serviceId}
            onClick={() => ephemeral.call(() => raum.credentials.ephemeral(ephForm, token))}
          >
            {ephemeral.state.status === 'loading' ? 'Issuing…' : 'Issue'}
          </button>
        </div>
        {ephemeral.state.status === 'success' && <CredentialCard cred={ephemeral.state.data} />}
        {ephemeral.state.status === 'error' && <div className="error">{ephemeral.state.message}</div>}
      </div>

      <div className="panel">
        <h2>Test Database Connection</h2>
        <div className="actions">
          <button className="btn btn-outline" disabled={testDb.state.status === 'loading'} onClick={() => testDb.call(() => raum.credentials.testDb(token))}>
            {testDb.state.status === 'loading' ? 'Testing…' : 'Test'}
          </button>
        </div>
        {testDb.state.status === 'success' && (
          <div className={`status-badge ${testDb.state.data ? 'status-ok' : 'status-fail'}`}>
            {testDb.state.data ? 'Database reachable' : 'Database unreachable'}
          </div>
        )}
        {testDb.state.status === 'error' && <div className="error">{testDb.state.message}</div>}
      </div>

    </div>
  )
}
