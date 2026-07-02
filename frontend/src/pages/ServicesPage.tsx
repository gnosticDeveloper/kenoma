import { useState } from 'react'
import { raum } from '../api/raum'
import { useApiCall } from '../hooks/useApiCall'
import { Feedback } from '../components/Feedback'
import { ServiceCard } from '../components/ServiceCard'
import { ServiceTable } from '../components/ServiceTable'
import type { ServiceRequest, ServiceResponse } from '../types'

interface Props { token: string }

export default function ServicesPage({ token }: Props) {
  const list = useApiCall<ServiceResponse[]>()

  const create = useApiCall<ServiceResponse>()
  const [createForm, setCreateForm] = useState<ServiceRequest>({ name: '', description: '' })

  const get = useApiCall<ServiceResponse>()
  const [getId, setGetId] = useState('')

  const update = useApiCall<ServiceResponse>()
  const [updateId, setUpdateId] = useState('')
  const [updateForm, setUpdateForm] = useState<ServiceRequest>({ name: '', description: '' })

  const del = useApiCall<void>()
  const [deleteId, setDeleteId] = useState('')

  return (
    <div className="page">

      <div className="panel">
        <h2>All Services</h2>
        <div className="actions">
          <button className="btn btn-outline" disabled={list.state.status === 'loading'} onClick={() => list.call(() => raum.services.list(token))}>
            {list.state.status === 'loading' ? 'Loading…' : 'Load'}
          </button>
        </div>
        {list.state.status === 'success' && <ServiceTable services={list.state.data} />}
        {list.state.status === 'error' && <div className="error">{list.state.message}</div>}
      </div>

      <div className="panel">
        <h2>Register Service</h2>
        <div className="fields">
          <div className="field">
            <label>Name</label>
            <input value={createForm.name} onChange={e => setCreateForm(f => ({ ...f, name: e.target.value }))} placeholder="my-service" />
          </div>
          <div className="field">
            <label>Description</label>
            <input value={createForm.description} onChange={e => setCreateForm(f => ({ ...f, description: e.target.value }))} placeholder="What this service does" />
          </div>
        </div>
        <div className="actions">
          <button className="btn btn-primary" disabled={create.state.status === 'loading'} onClick={() => create.call(() => raum.services.create(createForm, token))}>
            {create.state.status === 'loading' ? 'Registering…' : 'Register'}
          </button>
        </div>
        {create.state.status === 'success' && <ServiceCard svc={create.state.data} />}
        {create.state.status === 'error' && <div className="error">{create.state.message}</div>}
      </div>

      <div className="panel">
        <h2>Look Up Service</h2>
        <div className="fields">
          <div className="field">
            <label>Service ID</label>
            <input value={getId} onChange={e => setGetId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" />
          </div>
        </div>
        <div className="actions">
          <button className="btn btn-outline" disabled={get.state.status === 'loading' || !getId.trim()} onClick={() => get.call(() => raum.services.get(getId.trim(), token))}>
            {get.state.status === 'loading' ? 'Loading…' : 'Look up'}
          </button>
        </div>
        {get.state.status === 'success' && <ServiceCard svc={get.state.data} />}
        {get.state.status === 'error' && <div className="error">{get.state.message}</div>}
      </div>

      <div className="panel">
        <h2>Update Service</h2>
        <div className="fields">
          <div className="field">
            <label>Service ID</label>
            <input value={updateId} onChange={e => setUpdateId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" />
          </div>
          <div className="field">
            <label>Name</label>
            <input value={updateForm.name} onChange={e => setUpdateForm(f => ({ ...f, name: e.target.value }))} placeholder="my-service" />
          </div>
          <div className="field">
            <label>Description</label>
            <input value={updateForm.description} onChange={e => setUpdateForm(f => ({ ...f, description: e.target.value }))} placeholder="What this service does" />
          </div>
        </div>
        <div className="actions">
          <button className="btn btn-primary" disabled={update.state.status === 'loading' || !updateId.trim()} onClick={() => update.call(() => raum.services.update(updateId.trim(), updateForm, token))}>
            {update.state.status === 'loading' ? 'Updating…' : 'Update'}
          </button>
        </div>
        {update.state.status === 'success' && <ServiceCard svc={update.state.data} />}
        {update.state.status === 'error' && <div className="error">{update.state.message}</div>}
      </div>

      <div className="panel">
        <h2>Delete Service</h2>
        <div className="fields">
          <div className="field">
            <label>Service ID</label>
            <input value={deleteId} onChange={e => setDeleteId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-danger"
            disabled={del.state.status === 'loading' || !deleteId.trim()}
            onClick={() => {
              if (window.confirm(`Permanently delete service ${deleteId.trim()}?`)) {
                del.call(() => raum.services.delete(deleteId.trim(), token))
              }
            }}
          >
            {del.state.status === 'loading' ? 'Deleting…' : 'Delete'}
          </button>
        </div>
        <Feedback state={del.state} successLabel="Service deleted." />
      </div>

    </div>
  )
}
