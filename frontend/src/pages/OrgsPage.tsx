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
import type { OrgRequest, OrgResponse } from '../types'

interface Props { token: string }

const EMPTY_FORM: OrgRequest = { name: '', contactEmail: '', contactName: '' }

export default function OrgsPage({ token }: Props) {
  const { t } = useTranslation()
  const toast = useToast()

  const list = useApiCall<OrgResponse[]>()
  function reload() { list.call(() => raum.orgs.list(token)) }
  useEffect(reload, [token])
  const orgs = list.state.status === 'success' ? list.state.data : []

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<OrgResponse | null>(null)
  const [form, setForm] = useState<OrgRequest>(EMPTY_FORM)
  const save = useApiCall<OrgResponse>()
  const del = useApiCall<void>()

  useEffect(() => {
    if (save.state.status !== 'success') return
    setModalOpen(false)
    reload()
    toast.show(t(editing ? 'orgsPage.updated' : 'orgsPage.created'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [save.state])

  function openCreate() {
    setEditing(null)
    setForm(EMPTY_FORM)
    setModalOpen(true)
  }

  function openEdit(org: OrgResponse) {
    setEditing(org)
    setForm({ name: org.name, contactEmail: org.contactEmail, contactName: '' })
    setModalOpen(true)
  }

  function submit() {
    save.call(() => editing ? raum.orgs.update(editing.id, form, token) : raum.orgs.create(form, token))
  }

  function remove(org: OrgResponse) {
    if (!window.confirm(t('orgsPage.deleteConfirm', { name: org.name }))) return
    del.call(() => raum.orgs.delete(org.id, token)).then(() => {
      reload()
      toast.show(t('orgsPage.deleted'))
    })
  }

  const columns: Column<OrgResponse>[] = [
    { key: 'name', header: t('orgsPage.name'), render: o => o.name, sortValue: o => o.name },
    { key: 'contactEmail', header: t('orgsPage.contactEmail'), render: o => o.contactEmail, sortValue: o => o.contactEmail },
    {
      key: 'actions',
      header: '',
      render: o => (
        <RowActionsMenu actions={[
          { label: t('common.actions.edit'), onClick: () => openEdit(o) },
          { label: t('common.actions.delete'), onClick: () => remove(o), danger: true },
        ]} />
      ),
    },
  ]

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('orgsPage.title')}</h1>
          <p>{t('orgsPage.subtitle')}</p>
        </div>
      </div>

      <div className="panel">
        {list.state.status === 'error' && <Feedback state={list.state} />}
        <DataTable
          columns={columns}
          rows={orgs}
          rowKey={o => o.id}
          searchable
          searchText={o => `${o.name} ${o.contactEmail}`}
          onRowClick={openEdit}
          emptyLabel={t('orgsPage.emptyState')}
          headerAction={<button className="btn btn-primary" onClick={openCreate} type="button">{t('orgsPage.createAction')}</button>}
        />
      </div>

      <Modal open={modalOpen} onClose={() => setModalOpen(false)} title={t(editing ? 'orgsPage.editTitle' : 'orgsPage.createTitle')}>
        <div className="fields">
          <div className="field">
            <label>{t('orgsPage.name')}</label>
            <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="Acme Corp" />
          </div>
          <div className="field">
            <label>{t('orgsPage.contactEmail')}</label>
            <input value={form.contactEmail} onChange={e => setForm(f => ({ ...f, contactEmail: e.target.value }))} placeholder="admin@acme.com" />
          </div>
          <div className="field">
            <label>{t('orgsPage.contactName')}</label>
            <input value={form.contactName} onChange={e => setForm(f => ({ ...f, contactName: e.target.value }))} placeholder="Jane Doe" />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={save.state.status === 'loading' || !form.name.trim() || !form.contactEmail.trim() || !form.contactName.trim()}
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
