import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { bime } from '../api/bime'
import { useApiCall } from '../hooks/useApiCall'
import { useToast } from '../components/Toast'
import { Modal } from '../components/Modal'
import { DataTable, type Column } from '../components/DataTable'
import { RowActionsMenu } from '../components/RowActionsMenu'
import { Feedback } from '../components/Feedback'
import type { Permissions } from '../auth'
import type { OrgUnitResponse } from '../types'

interface Props {
  token: string
  permissions: Permissions
}

export default function BimeUnitsPage({ token, permissions }: Props) {
  const { t } = useTranslation()
  const toast = useToast()

  const list = useApiCall<OrgUnitResponse[]>()
  function reload() { list.call(() => bime.units.list(token)) }
  useEffect(reload, [token])
  const units = list.state.status === 'success' ? list.state.data : []

  const [createOpen, setCreateOpen] = useState(false)
  const [createName, setCreateName] = useState('')
  const create = useApiCall<OrgUnitResponse>()
  const deleteCall = useApiCall<void>()

  useEffect(() => {
    if (create.state.status !== 'success') return
    setCreateOpen(false)
    setCreateName('')
    reload()
    toast.show(t('bimeUnitsPage.created'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [create.state])

  function remove(unit: OrgUnitResponse) {
    if (!window.confirm(t('bimeUnitsPage.deleteConfirm', { name: unit.name }))) return
    deleteCall.call(() => bime.units.delete(unit.id, token)).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
      reload()
      toast.show(t('bimeUnitsPage.deleted'))
    })
  }

  const columns: Column<OrgUnitResponse>[] = [
    { key: 'name', header: t('bimeUnitsPage.name'), render: u => u.name, sortValue: u => u.name },
    {
      key: 'kind',
      header: t('bimeUnitsPage.kind'),
      render: u => (
        <span className={`status-badge ${u.standard ? 'status-ok' : ''}`}>
          {u.standard ? t('bimeUnitsPage.standard') : t('bimeUnitsPage.custom')}
        </span>
      ),
    },
    ...(permissions.canManageBime ? [{
      key: 'actions',
      header: '',
      render: (u: OrgUnitResponse) => (
        <RowActionsMenu actions={[
          { label: t('common.actions.delete'), onClick: () => remove(u), danger: true },
        ]} />
      ),
    }] : []),
  ]

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('bimeUnitsPage.title')}</h1>
          <p>{t('bimeUnitsPage.subtitle')}</p>
        </div>
      </div>

      <div className="panel">
        {list.state.status === 'error' && <Feedback state={list.state} />}
        <DataTable
          columns={columns}
          rows={units}
          rowKey={u => u.id}
          searchable
          searchText={u => u.name}
          emptyLabel={t('bimeUnitsPage.emptyState')}
          headerAction={permissions.canManageBime
            ? <button className="btn btn-primary" onClick={() => setCreateOpen(true)} type="button">{t('bimeUnitsPage.createAction')}</button>
            : undefined}
        />
      </div>

      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title={t('bimeUnitsPage.createTitle')}>
        <p className="panel-hint">{t('bimeUnitsPage.createHint')}</p>
        <div className="fields">
          <div className="field">
            <label>{t('bimeUnitsPage.name')}</label>
            <input value={createName} onChange={e => setCreateName(e.target.value)} placeholder="case" />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={create.state.status === 'loading' || !createName.trim()}
            onClick={() => create.call(() => bime.units.create({ name: createName.trim() }, token))}
          >
            {create.state.status === 'loading' ? t('common.actions.loading') : t('common.actions.create')}
          </button>
        </div>
        {create.state.status === 'error' && <Feedback state={create.state} />}
      </Modal>
    </div>
  )
}
