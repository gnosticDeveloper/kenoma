import { useState } from 'react'
import { raum } from '../api/raum'
import { useApiCall } from '../hooks/useApiCall'
import { Feedback } from '../components/Feedback'
import { OrgCard } from '../components/OrgCard'
import type { OrgRequest, OrgResponse } from '../types'

interface Props { token: string }

export default function OrgsPage({ token }: Props) {
  const create = useApiCall<OrgResponse>()
  const [createForm, setCreateForm] = useState<OrgRequest>({ name: '', contactEmail: '', contactName: '' })

  const get = useApiCall<OrgResponse>()
  const [getId, setGetId] = useState('')

  const update = useApiCall<OrgResponse>()
  const [updateId, setUpdateId] = useState('')
  const [updateForm, setUpdateForm] = useState<OrgRequest>({ name: '', contactEmail: '', contactName: '' })

  const del = useApiCall<void>()
  const [deleteId, setDeleteId] = useState('')

  return (
    <div className="page">

      <div className="panel">
        <h2>Create Organization</h2>
        <div className="fields">
          <div className="field">
            <label>Name</label>
            <input value={createForm.name} onChange={e => setCreateForm(f => ({ ...f, name: e.target.value }))} placeholder="Acme Corp" />
          </div>
          <div className="field">
            <label>Contact Email</label>
            <input value={createForm.contactEmail} onChange={e => setCreateForm(f => ({ ...f, contactEmail: e.target.value }))} placeholder="admin@acme.com" />
          </div>
          <div className="field">
            <label>Contact Name</label>
            <input value={createForm.contactName} onChange={e => setCreateForm(f => ({ ...f, contactName: e.target.value }))} placeholder="Jane Doe" />
          </div>
        </div>
        <div className="actions">
          <button className="btn btn-primary" disabled={create.state.status === 'loading'} onClick={() => create.call(() => raum.orgs.create(createForm, token))}>
            {create.state.status === 'loading' ? 'Creating…' : 'Create'}
          </button>
        </div>
        {create.state.status === 'success' && <OrgCard org={create.state.data} />}
        {create.state.status === 'error' && <div className="error">{create.state.message}</div>}
      </div>

      <div className="panel">
        <h2>Look Up Organization</h2>
        <div className="fields">
          <div className="field">
            <label>Organization ID</label>
            <input value={getId} onChange={e => setGetId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" />
          </div>
        </div>
        <div className="actions">
          <button className="btn btn-outline" disabled={get.state.status === 'loading' || !getId.trim()} onClick={() => get.call(() => raum.orgs.get(getId.trim(), token))}>
            {get.state.status === 'loading' ? 'Loading…' : 'Look up'}
          </button>
        </div>
        {get.state.status === 'success' && <OrgCard org={get.state.data} />}
        {get.state.status === 'error' && <div className="error">{get.state.message}</div>}
      </div>

      <div className="panel">
        <h2>Update Organization</h2>
        <div className="fields">
          <div className="field">
            <label>Organization ID</label>
            <input value={updateId} onChange={e => setUpdateId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" />
          </div>
          <div className="field">
            <label>Name</label>
            <input value={updateForm.name} onChange={e => setUpdateForm(f => ({ ...f, name: e.target.value }))} placeholder="Acme Corp" />
          </div>
          <div className="field">
            <label>Contact Email</label>
            <input value={updateForm.contactEmail} onChange={e => setUpdateForm(f => ({ ...f, contactEmail: e.target.value }))} placeholder="admin@acme.com" />
          </div>
          <div className="field">
            <label>Contact Name</label>
            <input value={updateForm.contactName} onChange={e => setUpdateForm(f => ({ ...f, contactName: e.target.value }))} placeholder="Jane Doe" />
          </div>
        </div>
        <div className="actions">
          <button className="btn btn-primary" disabled={update.state.status === 'loading' || !updateId.trim()} onClick={() => update.call(() => raum.orgs.update(updateId.trim(), updateForm, token))}>
            {update.state.status === 'loading' ? 'Updating…' : 'Update'}
          </button>
        </div>
        {update.state.status === 'success' && <OrgCard org={update.state.data} />}
        {update.state.status === 'error' && <div className="error">{update.state.message}</div>}
      </div>

      <div className="panel">
        <h2>Delete Organization</h2>
        <div className="fields">
          <div className="field">
            <label>Organization ID</label>
            <input value={deleteId} onChange={e => setDeleteId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-danger"
            disabled={del.state.status === 'loading' || !deleteId.trim()}
            onClick={() => {
              if (window.confirm(`Permanently delete org ${deleteId.trim()}?`)) {
                del.call(() => raum.orgs.delete(deleteId.trim(), token))
              }
            }}
          >
            {del.state.status === 'loading' ? 'Deleting…' : 'Delete'}
          </button>
        </div>
        <Feedback state={del.state} successLabel="Organization deleted." />
      </div>

    </div>
  )
}
