import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { bime } from '../api/bime'
import { useApiCall } from '../hooks/useApiCall'
import { useToast } from '../components/Toast'
import { Modal } from '../components/Modal'
import { DataTable, type Column } from '../components/DataTable'
import { Combobox, type ComboboxItem } from '../components/Combobox'
import { Feedback } from '../components/Feedback'
import type { BatchResponse, BatchStatus, RecallReport } from '../types'

interface Props {
  token: string
  canManage: boolean
  canRecall: boolean
  productItems: ComboboxItem[]
  locationItems: ComboboxItem[]
  variantItemsFor: (productId: string) => ComboboxItem[]
  variantLabel: (variantId: string) => string
  locationLabel: (locationId: string) => string
}

const STATUSES: BatchStatus[] = ['ACTIVE', 'RECALLED']

export default function BimeBatchesTab({
  token, canManage, canRecall,
  productItems, locationItems, variantItemsFor, variantLabel, locationLabel,
}: Props) {
  const { t } = useTranslation()
  const toast = useToast()

  const list = useApiCall<BatchResponse[]>()
  const [filterProduct, setFilterProduct] = useState<string | null>(null)
  const [filterVariant, setFilterVariant] = useState<string | null>(null)
  const [filterLocation, setFilterLocation] = useState<string | null>(null)
  const [filterStatus, setFilterStatus] = useState<BatchStatus | ''>('')
  const [expiringOnly, setExpiringOnly] = useState(false)

  function load() {
    list.call(() => bime.batches.list(token, {
      variantId: filterVariant ?? undefined,
      locationId: filterLocation ?? undefined,
      status: filterStatus || undefined,
      expiringWithinDays: expiringOnly ? 30 : undefined,
    }))
  }
  useEffect(() => { load() /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [filterVariant, filterLocation, filterStatus, expiringOnly])

  const rows = useMemo(() => {
    const data = list.state.status === 'success' ? list.state.data : []
    if (!filterProduct || filterVariant) return data
    const variantIds = new Set(variantItemsFor(filterProduct).map(v => v.id))
    return data.filter(b => variantIds.has(b.variantId))
  }, [list.state, filterProduct, filterVariant, variantItemsFor])

  // ── Recall ──
  const recallCall = useApiCall<BatchResponse>()
  const [recallTarget, setRecallTarget] = useState<BatchResponse | null>(null)
  const [recallNote, setRecallNote] = useState('')

  function submitRecall() {
    if (!recallTarget) return
    recallCall.call(() => bime.batches.recall(recallTarget.id, { note: recallNote.trim() || undefined }, token)).then(r => {
      if (!r.ok) { toast.show(r.message, 'error'); return }
      toast.show(t('bimeBatchesTab.recalled'))
      setRecallTarget(null); setRecallNote(''); load()
    })
  }
  function liftRecall(batch: BatchResponse) {
    if (!window.confirm(t('bimeBatchesTab.liftConfirm', { code: batch.batchCode }))) return
    bime.batches.liftRecall(batch.id, token).then(() => { toast.show(t('bimeBatchesTab.recallLifted')); load() })
      .catch(() => toast.show(t('common.errors.generic'), 'error'))
  }

  // ── Recall report ──
  const report = useApiCall<RecallReport>()
  const [reportOpen, setReportOpen] = useState(false)
  function openReport(batch: BatchResponse) {
    setReportOpen(true)
    report.call(() => bime.batches.recallReport(batch.id, token))
  }

  // ── Settings ──
  const [settingsOpen, setSettingsOpen] = useState(false)
  const settings = useApiCall<{ nearExpiryDays: number }>()
  const [nearExpiryDays, setNearExpiryDays] = useState(30)
  function openSettings() {
    setSettingsOpen(true)
    bime.batches.getSettings(token).then(s => setNearExpiryDays(s.nearExpiryDays)).catch(() => {})
  }
  function saveSettings() {
    settings.call(() => bime.batches.updateSettings({ nearExpiryDays }, token)).then(r => {
      if (!r.ok) { toast.show(r.message, 'error'); return }
      toast.show(t('bimeBatchesTab.settingsSaved')); setSettingsOpen(false)
    })
  }

  const soon = (d: string | null) => {
    if (!d) return false
    const days = (new Date(d).getTime() - Date.now()) / 86_400_000
    return days <= 30
  }

  const columns: Column<BatchResponse>[] = [
    { key: 'batchCode', header: t('bimeBatchesTab.code'), render: b => b.batchCode },
    { key: 'variant', header: t('bimeBatchesTab.variant'), render: b => variantLabel(b.variantId) },
    {
      key: 'expiry', header: t('bimeBatchesTab.expiry'),
      render: b => b.expiryDate
        ? <span className={soon(b.expiryDate) ? 'feedback-error' : undefined}>{b.expiryDate}</span>
        : <span className="td-muted">—</span>,
    },
    { key: 'onHand', header: t('bimeBatchesTab.onHand'), render: b => String(b.totalQuantity) },
    {
      key: 'status', header: t('bimeBatchesTab.status'),
      render: b => <span className={`status-badge ${b.status === 'ACTIVE' ? 'status-ok' : 'status-fail'}`}>
        {t(`bimeBatchesTab.statuses.${b.status}`)}
      </span>,
    },
    {
      key: 'locations', header: t('bimeBatchesTab.locations'),
      render: b => b.balances.length === 0
        ? <span className="td-muted">—</span>
        : b.balances.map(bl => `${locationLabel(bl.locationId)}: ${bl.quantity}`).join(', '),
    },
    {
      key: 'actions', header: '',
      render: b => (
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button className="btn btn-outline btn-sm" type="button" onClick={() => openReport(b)}>{t('bimeBatchesTab.report')}</button>
          {canRecall && b.status === 'ACTIVE' && (
            <button className="btn btn-outline btn-sm" type="button" onClick={() => { setRecallTarget(b); setRecallNote('') }}>{t('bimeBatchesTab.recall')}</button>
          )}
          {canRecall && b.status === 'RECALLED' && (
            <button className="btn btn-outline btn-sm" type="button" onClick={() => liftRecall(b)}>{t('bimeBatchesTab.lift')}</button>
          )}
        </div>
      ),
    },
  ]

  return (
    <div className="panel">
      <p className="panel-hint">{t('bimeBatchesTab.hint')}</p>
      <div className="fields">
        <div className="field">
          <label>{t('bimeBatchesTab.filterProduct')}</label>
          <Combobox items={productItems} value={filterProduct} onChange={id => { setFilterProduct(id); setFilterVariant(null) }} placeholder={t('bimeBatchesTab.allProducts')} />
        </div>
        <div className="field">
          <label>{t('bimeBatchesTab.filterVariant')}</label>
          <Combobox items={filterProduct ? variantItemsFor(filterProduct) : []} value={filterVariant} onChange={setFilterVariant} placeholder={t('bimeBatchesTab.allVariants')} disabled={!filterProduct} />
        </div>
        <div className="field">
          <label>{t('bimeBatchesTab.filterLocation')}</label>
          <Combobox items={locationItems} value={filterLocation} onChange={setFilterLocation} placeholder={t('bimeBatchesTab.allLocations')} />
        </div>
        <div className="field">
          <label>{t('bimeBatchesTab.filterStatus')}</label>
          <select value={filterStatus} onChange={e => setFilterStatus(e.target.value as BatchStatus | '')}>
            <option value="">{t('bimeBatchesTab.anyStatus')}</option>
            {STATUSES.map(s => <option key={s} value={s}>{t(`bimeBatchesTab.statuses.${s}`)}</option>)}
          </select>
        </div>
        <div className="field field-checkbox">
          <label>
            <input type="checkbox" checked={expiringOnly} onChange={e => setExpiringOnly(e.target.checked)} />
            {' '}{t('bimeBatchesTab.expiringOnly')}
          </label>
        </div>
      </div>
      {list.state.status === 'error' && <Feedback state={list.state} />}
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={b => b.id}
        emptyLabel={t('bimeBatchesTab.emptyState')}
        headerAction={
          <div style={{ display: 'flex', gap: 8 }}>
            <button className="btn btn-outline" type="button" onClick={load}>{t('common.actions.refresh')}</button>
            {canManage && <button className="btn btn-outline" type="button" onClick={openSettings}>{t('bimeBatchesTab.settings')}</button>}
          </div>
        }
      />

      <Modal open={recallTarget !== null} onClose={() => setRecallTarget(null)} title={t('bimeBatchesTab.recallTitle', { code: recallTarget?.batchCode ?? '' })}>
        <p className="panel-hint">{t('bimeBatchesTab.recallHint')}</p>
        <div className="fields">
          <div className="field">
            <label>{t('bimeBatchesTab.recallNote')}</label>
            <input value={recallNote} onChange={e => setRecallNote(e.target.value)} placeholder={t('bimeBatchesTab.recallNotePlaceholder')} />
          </div>
        </div>
        <div className="actions">
          <button className="btn btn-primary" disabled={recallCall.state.status === 'loading'} onClick={submitRecall}>
            {recallCall.state.status === 'loading' ? t('common.actions.loading') : t('bimeBatchesTab.recall')}
          </button>
        </div>
        {recallCall.state.status === 'error' && <Feedback state={recallCall.state} />}
      </Modal>

      <Modal open={reportOpen} onClose={() => setReportOpen(false)} title={t('bimeBatchesTab.reportTitle')}>
        {report.state.status === 'loading' && <p className="panel-hint">{t('common.actions.loading')}</p>}
        {report.state.status === 'error' && <Feedback state={report.state} />}
        {report.state.status === 'success' && (
          <>
            <h4>{t('bimeBatchesTab.affectedLocations')}</h4>
            {report.state.data.affectedLocations.length === 0
              ? <p className="panel-hint">{t('bimeBatchesTab.noStockLeft')}</p>
              : <ul>{report.state.data.affectedLocations.map(l => (
                  <li key={l.locationId}>{locationLabel(l.locationId)}: {l.quantity}</li>
                ))}</ul>}
            <h4>{t('bimeBatchesTab.movementHistory')}</h4>
            <ul>
              {report.state.data.history.map(m => (
                <li key={m.id ?? `${m.createdAt}-${m.delta}`}>
                  {m.createdAt} · {m.movementType} · {m.delta} · {locationLabel(m.locationId)}
                </li>
              ))}
            </ul>
          </>
        )}
      </Modal>

      <Modal open={settingsOpen} onClose={() => setSettingsOpen(false)} title={t('bimeBatchesTab.settingsTitle')}>
        <p className="panel-hint">{t('bimeBatchesTab.settingsHint')}</p>
        <div className="fields">
          <div className="field">
            <label>{t('bimeBatchesTab.nearExpiryDays')}</label>
            <input type="number" min={1} value={nearExpiryDays} onChange={e => setNearExpiryDays(parseInt(e.target.value, 10) || 1)} />
          </div>
        </div>
        <div className="actions">
          <button className="btn btn-primary" disabled={settings.state.status === 'loading'} onClick={saveSettings}>
            {settings.state.status === 'loading' ? t('common.actions.loading') : t('common.actions.save')}
          </button>
        </div>
        {settings.state.status === 'error' && <Feedback state={settings.state} />}
      </Modal>
    </div>
  )
}
