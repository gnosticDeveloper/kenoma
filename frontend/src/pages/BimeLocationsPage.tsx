import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { bime } from '../api/bime'
import { useApiCall } from '../hooks/useApiCall'
import { useToast } from '../components/Toast'
import { Modal } from '../components/Modal'
import { DataTable, type Column } from '../components/DataTable'
import { RowActionsMenu } from '../components/RowActionsMenu'
import { CopyButton } from '../components/CopyButton'
import { Feedback } from '../components/Feedback'
import type { Permissions } from '../auth'
import type { LocationRequest, LocationResponse } from '../types'

interface Props {
  token: string
  permissions: Permissions
}

const EMPTY_FORM: LocationRequest = { name: '', code: '', notificationEmail: '' }

export default function BimeLocationsPage({ token, permissions }: Props) {
  const { t } = useTranslation()
  const toast = useToast()

  const list = useApiCall<LocationResponse[]>()
  function reload() { list.call(() => bime.locations.list(token)) }
  useEffect(reload, [token])
  const locations = list.state.status === 'success' ? list.state.data : []

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<LocationResponse | null>(null)
  const [form, setForm] = useState<LocationRequest>(EMPTY_FORM)
  const save = useApiCall<LocationResponse>()
  const deactivate = useApiCall<void>()

  useEffect(() => {
    if (save.state.status !== 'success') return
    setModalOpen(false)
    reload()
    toast.show(t(editing ? 'bimeLocationsPage.updated' : 'bimeLocationsPage.created'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [save.state])

  function openCreate() {
    setEditing(null)
    setForm(EMPTY_FORM)
    setModalOpen(true)
  }

  function openEdit(loc: LocationResponse) {
    setEditing(loc)
    setForm({ name: loc.name, code: loc.code, isActive: loc.isActive, notificationEmail: loc.notificationEmail ?? '' })
    setModalOpen(true)
  }

  function submit() {
    save.call(() => editing ? bime.locations.update(editing.id, form, token) : bime.locations.create(form, token))
  }

  function remove(loc: LocationResponse) {
    if (!window.confirm(t('bimeLocationsPage.deactivateConfirm', { name: loc.name }))) return
    deactivate.call(() => bime.locations.deactivate(loc.id, token)).then(() => {
      reload()
      toast.show(t('bimeLocationsPage.deactivated'))
    })
  }

  const columns: Column<LocationResponse>[] = [
    { key: 'name', header: t('bimeLocationsPage.name'), render: l => l.name, sortValue: l => l.name },
    { key: 'code', header: t('bimeLocationsPage.code'), render: l => <span className="td-muted">{l.code}</span> },
    { key: 'notificationEmail', header: t('bimeLocationsPage.notificationEmail'), render: l => <span className="td-muted">{l.notificationEmail ?? '—'}</span> },
    {
      key: 'active',
      header: t('bimeLocationsPage.active'),
      render: l => (
        <span className={`status-badge ${l.isActive ? 'status-ok' : 'status-fail'}`}>
          {l.isActive ? t('bimeLocationsPage.active') : t('bimeLocationsPage.inactive')}
        </span>
      ),
    },
    ...(permissions.canManageBime ? [{
      key: 'actions',
      header: '',
      render: (l: LocationResponse) => (
        <RowActionsMenu actions={[
          { label: t('common.actions.edit'), onClick: () => openEdit(l) },
          { label: t('common.actions.deactivate'), onClick: () => remove(l), danger: true },
        ]} />
      ),
    }] : []),
  ]

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('bimeLocationsPage.title')}</h1>
          <p>{t('bimeLocationsPage.subtitle')}</p>
        </div>
      </div>

      <div className="panel">
        {list.state.status === 'error' && <Feedback state={list.state} />}
        <DataTable
          columns={columns}
          rows={locations}
          rowKey={l => l.id}
          searchable
          searchText={l => `${l.name} ${l.code} ${l.notificationEmail ?? ''}`}
          onRowClick={permissions.canManageBime ? openEdit : undefined}
          emptyLabel={t('bimeLocationsPage.emptyState')}
          headerAction={permissions.canManageBime
            ? <button className="btn btn-primary" onClick={openCreate} type="button">{t('bimeLocationsPage.createAction')}</button>
            : undefined}
        />
      </div>

      <Modal open={modalOpen} onClose={() => setModalOpen(false)} title={t(editing ? 'bimeLocationsPage.editTitle' : 'bimeLocationsPage.createTitle')}>
        <div className="fields">
          <div className="field">
            <label>{t('bimeLocationsPage.name')}</label>
            <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="Main Store" />
          </div>
          <div className="field">
            <label>{t('bimeLocationsPage.code')}</label>
            <input value={form.code} onChange={e => setForm(f => ({ ...f, code: e.target.value }))} placeholder="WH-01" />
          </div>
          <div className="field">
            <label>{t('bimeLocationsPage.notificationEmail')}</label>
            <input
              type="email"
              value={form.notificationEmail ?? ''}
              onChange={e => setForm(f => ({ ...f, notificationEmail: e.target.value }))}
              placeholder="alerts@yourstore.com"
            />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={save.state.status === 'loading' || !form.name.trim() || !form.code.trim()}
            onClick={submit}
          >
            {save.state.status === 'loading' ? t('common.actions.loading') : t(editing ? 'common.actions.save' : 'common.actions.create')}
          </button>
        </div>
        {save.state.status === 'error' && <Feedback state={save.state} />}
        {editing && (
          <details className="id-disclosure">
            <summary>{t('common.fields.id')}</summary>
            <div className="id-disclosure-row">
              <span className="id-disclosure-value">{editing.id}</span>
              <CopyButton text={editing.id} />
            </div>
          </details>
        )}
      </Modal>
    </div>
  )
}
