import { useState } from 'react'
import { bime } from '../api/bime'
import { useApiCall } from '../hooks/useApiCall'
import { Feedback } from '../components/Feedback'
import { CopyButton } from '../components/CopyButton'
import type { Permissions } from '../auth'
import type { ProductMetadataResponse } from '../types'

interface Props {
  token: string
  permissions: Permissions
}

function MetadataTable({ definitions }: { definitions: ProductMetadataResponse[] }) {
  if (definitions.length === 0) {
    return <div className="empty-state">No metadata definitions yet.</div>
  }
  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>Name</th>
          <th>Options</th>
          <th>ID</th>
        </tr>
      </thead>
      <tbody>
        {definitions.map(m => (
          <tr key={m.id}>
            <td>{m.name}</td>
            <td className="td-muted">
              {m.options.length === 0
                ? '—'
                : m.options.map(o => (
                  <span key={o.id} className="role-badge" title={o.id} style={{ marginRight: '6px' }}>
                    {o.value}
                  </span>
                ))}
            </td>
            <td>
              <div className="td-id">
                <span>{m.id}</span>
                <CopyButton text={m.id} />
              </div>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export default function BimeMetadataPage({ token, permissions }: Props) {
  const list = useApiCall<ProductMetadataResponse[]>()

  const create = useApiCall<ProductMetadataResponse>()
  const [createName, setCreateName] = useState('')

  const deleteCall = useApiCall<void>()
  const [deleteId, setDeleteId] = useState('')

  const addOption = useApiCall<unknown>()
  const [optMetadataId, setOptMetadataId] = useState('')
  const [optValue, setOptValue] = useState('')

  const removeOption = useApiCall<void>()
  const [removeMetadataId, setRemoveMetadataId] = useState('')
  const [removeOptionId, setRemoveOptionId] = useState('')

  return (
    <div className="page">

      {/* ── All Metadata Definitions ── */}
      <div className="panel">
        <h2>All Metadata Definitions</h2>
        <div className="actions">
          <button
            className="btn btn-outline"
            disabled={list.state.status === 'loading'}
            onClick={() => list.call(() => bime.metadata.list(token))}
          >
            {list.state.status === 'loading' ? 'Loading…' : 'Load'}
          </button>
        </div>
        {list.state.status === 'success' && <MetadataTable definitions={list.state.data} />}
        {list.state.status === 'error' && <div className="error">{list.state.message}</div>}
      </div>

      {/* ── Create Metadata Definition ── */}
      {permissions.canManageBime && (
        <div className="panel">
          <h2>Create Metadata Definition</h2>
          <div className="fields">
            <div className="field">
              <label>Name</label>
              <input value={createName} onChange={e => setCreateName(e.target.value)} placeholder="Colour" />
            </div>
          </div>
          <div className="actions">
            <button
              className="btn btn-primary"
              disabled={create.state.status === 'loading' || !createName.trim()}
              onClick={() => create.call(() => bime.metadata.create({ name: createName.trim() }, token))}
            >
              {create.state.status === 'loading' ? 'Creating…' : 'Create'}
            </button>
          </div>
          <Feedback state={create.state} successLabel="Metadata definition created." />
        </div>
      )}

      {/* ── Add Option ── */}
      {permissions.canManageBime && (
        <div className="panel">
          <h2>Add Option</h2>
          <div className="fields">
            <div className="field">
              <label>Metadata ID</label>
              <input value={optMetadataId} onChange={e => setOptMetadataId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" spellCheck={false} />
            </div>
            <div className="field">
              <label>Value</label>
              <input value={optValue} onChange={e => setOptValue(e.target.value)} placeholder="Red" />
            </div>
          </div>
          <div className="actions">
            <button
              className="btn btn-primary"
              disabled={addOption.state.status === 'loading' || !optMetadataId.trim() || !optValue.trim()}
              onClick={() => addOption.call(() => bime.metadata.addOption(optMetadataId.trim(), { value: optValue.trim() }, token))}
            >
              {addOption.state.status === 'loading' ? 'Adding…' : 'Add Option'}
            </button>
          </div>
          <Feedback state={addOption.state} successLabel="Option added." />
        </div>
      )}

      {/* ── Remove Option ── */}
      {permissions.canManageBime && (
        <div className="panel">
          <h2>Remove Option</h2>
          <div className="fields">
            <div className="field">
              <label>Metadata ID</label>
              <input value={removeMetadataId} onChange={e => setRemoveMetadataId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" spellCheck={false} />
            </div>
            <div className="field">
              <label>Option ID</label>
              <input value={removeOptionId} onChange={e => setRemoveOptionId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" spellCheck={false} />
            </div>
          </div>
          <div className="actions">
            <button
              className="btn btn-danger"
              disabled={removeOption.state.status === 'loading' || !removeMetadataId.trim() || !removeOptionId.trim()}
              onClick={() => {
                if (!window.confirm(`Remove option ${removeOptionId.trim()}?`)) return
                removeOption.call(() => bime.metadata.removeOption(removeMetadataId.trim(), removeOptionId.trim(), token))
              }}
            >
              {removeOption.state.status === 'loading' ? 'Removing…' : 'Remove'}
            </button>
          </div>
          <Feedback state={removeOption.state} successLabel="Option removed." />
        </div>
      )}

      {/* ── Delete Metadata Definition ── */}
      {permissions.canManageBime && (
        <div className="panel">
          <h2>Delete Metadata Definition</h2>
          <p className="panel-hint">Fails if the definition is still assigned to any product.</p>
          <div className="fields">
            <div className="field">
              <label>Metadata ID</label>
              <input value={deleteId} onChange={e => setDeleteId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" spellCheck={false} />
            </div>
          </div>
          <div className="actions">
            <button
              className="btn btn-danger"
              disabled={deleteCall.state.status === 'loading' || !deleteId.trim()}
              onClick={() => {
                if (!window.confirm(`Delete metadata definition ${deleteId.trim()}? This cannot be undone.`)) return
                deleteCall.call(() => bime.metadata.delete(deleteId.trim(), token))
              }}
            >
              {deleteCall.state.status === 'loading' ? 'Deleting…' : 'Delete'}
            </button>
          </div>
          <Feedback state={deleteCall.state} successLabel="Metadata definition deleted." />
        </div>
      )}

    </div>
  )
}
