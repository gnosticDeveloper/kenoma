import { useState } from 'react'
import { bime } from '../api/bime'
import { useApiCall } from '../hooks/useApiCall'
import { Feedback } from '../components/Feedback'
import { CopyButton } from '../components/CopyButton'
import type { Permissions } from '../auth'
import type { LocationRequest, LocationResponse } from '../types'

interface Props {
  token: string
  permissions: Permissions
}

const EMPTY_FORM: LocationRequest = { name: '', code: '' }

function LocationsTable({ locations }: { locations: LocationResponse[] }) {
  if (locations.length === 0) {
    return <div className="empty-state">No locations registered yet.</div>
  }
  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>Name</th>
          <th>Code</th>
          <th>Active</th>
          <th>ID</th>
        </tr>
      </thead>
      <tbody>
        {locations.map(l => (
          <tr key={l.id}>
            <td>{l.name}</td>
            <td className="td-muted">{l.code}</td>
            <td>
              <span className={`status-badge ${l.isActive ? 'status-ok' : 'status-fail'}`}>
                {l.isActive ? 'Active' : 'Inactive'}
              </span>
            </td>
            <td>
              <div className="td-id">
                <span>{l.id}</span>
                <CopyButton text={l.id} />
              </div>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export default function BimeLocationsPage({ token, permissions }: Props) {
  const list = useApiCall<LocationResponse[]>()

  const create = useApiCall<LocationResponse>()
  const [createForm, setCreateForm] = useState<LocationRequest>(EMPTY_FORM)

  const update = useApiCall<LocationResponse>()
  const [updateId, setUpdateId] = useState('')
  const [updateForm, setUpdateForm] = useState<LocationRequest>(EMPTY_FORM)

  const deactivate = useApiCall<void>()
  const [deactivateId, setDeactivateId] = useState('')

  return (
    <div className="page">

      {/* ── All Locations ── */}
      <div className="panel">
        <h2>All Locations</h2>
        <div className="actions">
          <button
            className="btn btn-outline"
            disabled={list.state.status === 'loading'}
            onClick={() => list.call(() => bime.locations.list(token))}
          >
            {list.state.status === 'loading' ? 'Loading…' : 'Load'}
          </button>
        </div>
        {list.state.status === 'success' && <LocationsTable locations={list.state.data} />}
        {list.state.status === 'error' && <div className="error">{list.state.message}</div>}
      </div>

      {/* ── Create Location ── */}
      {permissions.canManageBime && (
        <div className="panel">
          <h2>Create Location</h2>
          <div className="fields">
            <div className="field">
              <label>Name</label>
              <input value={createForm.name} onChange={e => setCreateForm(f => ({ ...f, name: e.target.value }))} placeholder="Main Store" />
            </div>
            <div className="field">
              <label>Code</label>
              <input value={createForm.code} onChange={e => setCreateForm(f => ({ ...f, code: e.target.value }))} placeholder="WH-01" />
            </div>
          </div>
          <div className="actions">
            <button
              className="btn btn-primary"
              disabled={create.state.status === 'loading' || !createForm.name.trim() || !createForm.code.trim()}
              onClick={() => create.call(() => bime.locations.create(createForm, token))}
            >
              {create.state.status === 'loading' ? 'Creating…' : 'Create'}
            </button>
          </div>
          <Feedback state={create.state} successLabel="Location created." />
        </div>
      )}

      {/* ── Update Location ── */}
      {permissions.canManageBime && (
        <div className="panel">
          <h2>Update Location</h2>
          <div className="fields">
            <div className="field">
              <label>Location ID</label>
              <input value={updateId} onChange={e => setUpdateId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" spellCheck={false} />
            </div>
            <div className="field">
              <label>Name</label>
              <input value={updateForm.name} onChange={e => setUpdateForm(f => ({ ...f, name: e.target.value }))} />
            </div>
            <div className="field">
              <label>Code</label>
              <input value={updateForm.code} onChange={e => setUpdateForm(f => ({ ...f, code: e.target.value }))} />
            </div>
          </div>
          <div className="actions">
            <button
              className="btn btn-primary"
              disabled={update.state.status === 'loading' || !updateId.trim() || !updateForm.name.trim() || !updateForm.code.trim()}
              onClick={() => update.call(() => bime.locations.update(updateId.trim(), updateForm, token))}
            >
              {update.state.status === 'loading' ? 'Updating…' : 'Update'}
            </button>
          </div>
          <Feedback state={update.state} successLabel="Location updated." />
        </div>
      )}

      {/* ── Deactivate Location ── */}
      {permissions.canManageBime && (
        <div className="panel">
          <h2>Deactivate Location</h2>
          <div className="fields">
            <div className="field">
              <label>Location ID</label>
              <input value={deactivateId} onChange={e => setDeactivateId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" spellCheck={false} />
            </div>
          </div>
          <div className="actions">
            <button
              className="btn btn-danger"
              disabled={deactivate.state.status === 'loading' || !deactivateId.trim()}
              onClick={() => {
                if (!window.confirm(`Deactivate location ${deactivateId.trim()}?`)) return
                deactivate.call(() => bime.locations.deactivate(deactivateId.trim(), token))
              }}
            >
              {deactivate.state.status === 'loading' ? 'Deactivating…' : 'Deactivate'}
            </button>
          </div>
          <Feedback state={deactivate.state} successLabel="Location deactivated." />
        </div>
      )}

    </div>
  )
}
