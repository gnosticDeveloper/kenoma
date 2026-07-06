import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { raum } from '../api/raum'
import { useApiCall } from '../hooks/useApiCall'
import { useToast } from '../components/Toast'
import { Modal } from '../components/Modal'
import { DataTable, type Column } from '../components/DataTable'
import { RowActionsMenu } from '../components/RowActionsMenu'
import { CopyButton } from '../components/CopyButton'
import { Feedback } from '../components/Feedback'
import type { ServiceRequest, ServiceResponse } from '../types'

interface Props { token: string }

const EMPTY_FORM: ServiceRequest = { name: '', description: '' }

export default function ServicesPage({ token }: Props) {
  const { t } = useTranslation()
  const toast = useToast()

  const list = useApiCall<ServiceResponse[]>()
  function reload() { list.call(() => raum.services.list(token)) }
  useEffect(reload, [token])
  const services = list.state.status === 'success' ? list.state.data : []

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<ServiceResponse | null>(null)
  const [form, setForm] = useState<ServiceRequest>(EMPTY_FORM)
  const save = useApiCall<ServiceResponse>()
  const del = useApiCall<void>()

  useEffect(() => {
    if (save.state.status !== 'success') return
    setModalOpen(false)
    reload()
    toast.show(t(editing ? 'servicesPage.updated' : 'servicesPage.created'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [save.state])

  function openCreate() {
    setEditing(null)
    setForm(EMPTY_FORM)
    setModalOpen(true)
  }

  function openEdit(svc: ServiceResponse) {
    setEditing(svc)
    setForm({ name: svc.name, description: svc.description })
    setModalOpen(true)
  }

  function submit() {
    save.call(() => editing ? raum.services.update(editing.id, form, token) : raum.services.create(form, token))
  }

  function remove(svc: ServiceResponse) {
    if (!window.confirm(t('servicesPage.deleteConfirm', { name: svc.name }))) return
    del.call(() => raum.services.delete(svc.id, token)).then(() => {
      reload()
      toast.show(t('servicesPage.deleted'))
    })
  }

  const columns: Column<ServiceResponse>[] = [
    { key: 'name', header: t('servicesPage.name'), render: s => s.name, sortValue: s => s.name },
    { key: 'description', header: t('servicesPage.description'), render: s => <span className="td-muted">{s.description}</span> },
    {
      key: 'actions',
      header: '',
      render: s => (
        <RowActionsMenu actions={[
          { label: t('common.actions.edit'), onClick: () => openEdit(s) },
          { label: t('common.actions.delete'), onClick: () => remove(s), danger: true },
        ]} />
      ),
    },
  ]

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('servicesPage.title')}</h1>
          <p>{t('servicesPage.subtitle')}</p>
        </div>
      </div>

      <div className="panel">
        {list.state.status === 'error' && <Feedback state={list.state} />}
        <DataTable
          columns={columns}
          rows={services}
          rowKey={s => s.id}
          searchable
          searchText={s => `${s.name} ${s.description}`}
          onRowClick={openEdit}
          emptyLabel={t('servicesPage.emptyState')}
          headerAction={<button className="btn btn-primary" onClick={openCreate} type="button">{t('servicesPage.createAction')}</button>}
        />
      </div>

      <Modal open={modalOpen} onClose={() => setModalOpen(false)} title={t(editing ? 'servicesPage.editTitle' : 'servicesPage.createTitle')}>
        <div className="fields">
          <div className="field">
            <label>{t('servicesPage.name')}</label>
            <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="my-service" />
          </div>
          <div className="field">
            <label>{t('servicesPage.description')}</label>
            <input value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} placeholder="What this service does" />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={save.state.status === 'loading' || !form.name.trim()}
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
