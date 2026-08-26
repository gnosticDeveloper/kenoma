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
import { CloseIcon } from '../components/icons'
import type { Permissions } from '../auth'
import type { ProductMetadataResponse } from '../types'

interface Props {
  token: string
  permissions: Permissions
}

export default function BimeMetadataPage({ token, permissions }: Props) {
  const { t } = useTranslation()
  const toast = useToast()

  const list = useApiCall<ProductMetadataResponse[]>()
  function reload() { list.call(() => bime.metadata.list(token)) }
  useEffect(reload, [token])
  const definitions = list.state.status === 'success' ? list.state.data : []

  const [createOpen, setCreateOpen] = useState(false)
  const [createName, setCreateName] = useState('')
  const create = useApiCall<ProductMetadataResponse>()

  const [optionTarget, setOptionTarget] = useState<ProductMetadataResponse | null>(null)
  const [optionValue, setOptionValue] = useState('')
  const [optionCode, setOptionCode] = useState('')
  const addOption = useApiCall<unknown>()
  const removeOption = useApiCall<void>()
  const deleteCall = useApiCall<void>()

  useEffect(() => {
    if (create.state.status !== 'success') return
    setCreateOpen(false)
    setCreateName('')
    reload()
    toast.show(t('bimeMetadataPage.created'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [create.state])

  useEffect(() => {
    if (addOption.state.status !== 'success') return
    setOptionTarget(null)
    setOptionValue('')
    setOptionCode('')
    reload()
    toast.show(t('bimeMetadataPage.optionAdded'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [addOption.state])

  function removeOptionFrom(def: ProductMetadataResponse, optionId: string, value: string) {
    if (!window.confirm(t('bimeMetadataPage.removeOptionConfirm', { value }))) return
    removeOption.call(() => bime.metadata.removeOption(def.id, optionId, token)).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
      reload()
      toast.show(t('bimeMetadataPage.optionRemoved'))
    })
  }

  function remove(def: ProductMetadataResponse) {
    if (!window.confirm(t('bimeMetadataPage.deleteConfirm', { name: def.name }))) return
    deleteCall.call(() => bime.metadata.delete(def.id, token)).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
      reload()
      toast.show(t('bimeMetadataPage.deleted'))
    })
  }

  const columns: Column<ProductMetadataResponse>[] = [
    { key: 'name', header: t('bimeMetadataPage.name'), render: m => m.name, sortValue: m => m.name },
    {
      key: 'options',
      header: t('bimeMetadataPage.options'),
      render: m => m.options.length === 0 ? (
        <span className="td-muted">{t('bimeMetadataPage.noOptions')}</span>
      ) : (
        <div className="role-chips">
          {m.options.map(o => (
            <span key={o.id} className="role-badge">
              {o.value} <span className="td-muted">({o.code})</span>
              {permissions.canManageBime && (
                <button
                  type="button"
                  onClick={e => { e.stopPropagation(); removeOptionFrom(m, o.id, o.value) }}
                  style={{ background: 'none', border: 'none', color: 'inherit', cursor: 'pointer', marginLeft: 4, display: 'inline-flex', verticalAlign: 'middle' }}
                  aria-label={t('common.actions.delete')}
                >
                  <CloseIcon width={10} height={10} />
                </button>
              )}
            </span>
          ))}
        </div>
      ),
    },
    ...(permissions.canManageBime ? [{
      key: 'actions',
      header: '',
      render: (m: ProductMetadataResponse) => (
        <RowActionsMenu actions={[
          { label: t('bimeMetadataPage.addOption'), onClick: () => { setOptionTarget(m); setOptionValue(''); setOptionCode('') } },
          { label: t('common.actions.delete'), onClick: () => remove(m), danger: true },
        ]} />
      ),
    }] : []),
  ]

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('bimeMetadataPage.title')}</h1>
          <p>{t('bimeMetadataPage.subtitle')}</p>
        </div>
      </div>

      <div className="panel">
        {list.state.status === 'error' && <Feedback state={list.state} />}
        <DataTable
          columns={columns}
          rows={definitions}
          rowKey={m => m.id}
          searchable
          searchText={m => m.name}
          emptyLabel={t('bimeMetadataPage.emptyState')}
          headerAction={permissions.canManageBime
            ? <button className="btn btn-primary" onClick={() => setCreateOpen(true)} type="button">{t('bimeMetadataPage.createAction')}</button>
            : undefined}
        />
      </div>

      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title={t('bimeMetadataPage.createTitle')}>
        <div className="fields">
          <div className="field">
            <label>{t('bimeMetadataPage.name')}</label>
            <input value={createName} onChange={e => setCreateName(e.target.value)} placeholder="Colour" />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={create.state.status === 'loading' || !createName.trim()}
            onClick={() => create.call(() => bime.metadata.create({ name: createName.trim() }, token))}
          >
            {create.state.status === 'loading' ? t('common.actions.loading') : t('common.actions.create')}
          </button>
        </div>
        {create.state.status === 'error' && <Feedback state={create.state} />}
      </Modal>

      <Modal
        open={optionTarget !== null}
        onClose={() => setOptionTarget(null)}
        title={optionTarget ? t('bimeMetadataPage.addOptionTitle', { name: optionTarget.name }) : ''}
      >
        <div className="fields">
          <div className="field">
            <label>{t('bimeMetadataPage.optionValue')}</label>
            <input value={optionValue} onChange={e => setOptionValue(e.target.value)} placeholder="Red" />
          </div>
          <div className="field">
            <label>{t('bimeMetadataPage.optionCode')}</label>
            <input
              value={optionCode}
              onChange={e => setOptionCode(e.target.value.toUpperCase())}
              placeholder="RED"
            />
            <span className="panel-hint">{t('bimeMetadataPage.optionCodeHint')}</span>
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={addOption.state.status === 'loading' || !optionValue.trim() || !optionTarget}
            onClick={() => optionTarget && addOption.call(() => bime.metadata.addOption(
              optionTarget.id,
              { value: optionValue.trim(), code: optionCode.trim() || undefined },
              token,
            ))}
          >
            {addOption.state.status === 'loading' ? t('common.actions.loading') : t('common.actions.save')}
          </button>
        </div>
        {addOption.state.status === 'error' && <Feedback state={addOption.state} />}
        {optionTarget && (
          <details className="id-disclosure">
            <summary>{t('common.fields.id')}</summary>
            <div className="id-disclosure-row">
              <span className="id-disclosure-value">{optionTarget.id}</span>
              <CopyButton text={optionTarget.id} />
            </div>
          </details>
        )}
      </Modal>
    </div>
  )
}
