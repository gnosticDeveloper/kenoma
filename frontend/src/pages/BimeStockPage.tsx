import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { bime } from '../api/bime'
import { createCache } from '../lib/cache'
import { useApiCall } from '../hooks/useApiCall'
import { useDebouncedEffect } from '../hooks/useDebouncedEffect'
import { useToast } from '../components/Toast'
import { Modal } from '../components/Modal'
import { Tabs } from '../components/Tabs'
import { DataTable, type Column } from '../components/DataTable'
import { Combobox } from '../components/Combobox'
import { Feedback } from '../components/Feedback'
import type { Permissions } from '../auth'
import { RowActionsMenu } from '../components/RowActionsMenu'
import type {
  LocationResponse,
  MovementType,
  ProductResponse,
  ProductVariantResponse,
  StockAlertResponse,
  StockAlertThresholdRequest,
  StockAlertThresholdResponse,
  StockBalanceResponse,
  StockMovementResponse,
} from '../types'

interface Props {
  token: string
  permissions: Permissions
}

const MOVEMENT_TYPES: MovementType[] = ['INBOUND', 'OUTBOUND', 'ADJUSTMENT']

const FILTER_DEBOUNCE_MS = 500
const CACHE_TTL_MS = 3 * 60 * 1000
const movementsCache = createCache<StockMovementResponse[]>(CACHE_TTL_MS)
const balancesCache = createCache<StockBalanceResponse[]>(CACHE_TTL_MS)
const thresholdsCache = createCache<StockAlertThresholdResponse[]>(CACHE_TTL_MS)
const alertsCache = createCache<StockAlertResponse[]>(CACHE_TTL_MS)

function filterCacheKey(variantId: string | null, locationId: string | null): string {
  return `${variantId ?? ''}|${locationId ?? ''}`
}

type VariantInfo = { productId: string; sku: string | null; productName: string; optionsLabel: string }

export default function BimeStockPage({ token, permissions }: Props) {
  const { t } = useTranslation()
  const toast = useToast()
  const [activeTab, setActiveTab] = useState('movements')

  const locations = useApiCall<LocationResponse[]>()
  const products = useApiCall<ProductResponse[]>()
  const [variantsByProduct, setVariantsByProduct] = useState<Record<string, ProductVariantResponse[]>>({})
  const [variantLookup, setVariantLookup] = useState<Record<string, VariantInfo>>({})

  useEffect(() => {
    locations.call(() => bime.locations.list(token))
    products.call(() => bime.products.list(token))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (products.state.status !== 'success') return
    let cancelled = false
    Promise.all(products.state.data.map(p => bime.products.get(p.id, token))).then(full => {
      if (cancelled) return
      const byProduct: Record<string, ProductVariantResponse[]> = {}
      const lookup: Record<string, VariantInfo> = {}
      full.forEach(p => {
        const vs = p.variants ?? []
        byProduct[p.id] = vs
        vs.forEach(v => { lookup[v.id] = { productId: p.id, sku: v.sku, productName: p.name, optionsLabel: v.options.map(o => o.value).join(', ') } })
      })
      setVariantsByProduct(byProduct)
      setVariantLookup(lookup)
    }).catch(() => {})
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [products.state.status])

  const locationLookup: Record<string, LocationResponse> = {}
  if (locations.state.status === 'success') locations.state.data.forEach(l => { locationLookup[l.id] = l })
  const locationItems = (locations.state.status === 'success' ? locations.state.data : []).map(l => ({ id: l.id, label: l.name, sublabel: l.code }))
  const productItems = (products.state.status === 'success' ? products.state.data : []).map(p => ({ id: p.id, label: p.name, sublabel: p.sku }))

  function filterByProduct<T extends { variantId: string }>(rows: T[], productId: string | null): T[] {
    if (!productId) return rows
    const variantIds = new Set((variantsByProduct[productId] ?? []).map(v => v.id))
    return rows.filter(r => variantIds.has(r.variantId))
  }

  function variantItemsFor(productId: string) {
    return (variantsByProduct[productId] ?? []).map(v => ({
      id: v.id,
      label: v.sku ?? v.id.slice(0, 8) + '…',
      sublabel: v.options.map(o => o.value).join(', '),
    }))
  }

  function variantLabel(variantId: string): string {
    const info = variantLookup[variantId]
    if (!info) return variantId.slice(0, 8) + '…'
    return info.optionsLabel ? `${info.productName} — ${info.sku ?? '—'} (${info.optionsLabel})` : `${info.productName} — ${info.sku ?? '—'}`
  }

  function locationLabel(locationId: string): string {
    const loc = locationLookup[locationId]
    return loc ? `${loc.name} (${loc.code})` : locationId.slice(0, 8) + '…'
  }

  // ── Record movement ──
  const [recordOpen, setRecordOpen] = useState(false)
  const [recordProductId, setRecordProductId] = useState<string | null>(null)
  const [variantId, setVariantId] = useState<string | null>(null)
  const [locationId, setLocationId] = useState<string | null>(null)
  const [movementType, setMovementType] = useState<MovementType>('INBOUND')
  const [delta, setDelta] = useState(0)
  const [note, setNote] = useState('')
  const record = useApiCall<StockMovementResponse>()

  const movements = useApiCall<StockMovementResponse[]>()
  const [movFilterProduct, setMovFilterProduct] = useState<string | null>(null)
  const [movFilterVariant, setMovFilterVariant] = useState<string | null>(null)
  const [movFilterLocation, setMovFilterLocation] = useState<string | null>(null)

  function loadMovements() {
    movements.call(() => movementsCache.get(filterCacheKey(movFilterVariant, movFilterLocation), () => bime.stock.listMovements(token, {
      variantId: movFilterVariant ?? undefined,
      locationId: movFilterLocation ?? undefined,
    })))
  }
  useDebouncedEffect(loadMovements, [movFilterVariant, movFilterLocation], FILTER_DEBOUNCE_MS)

  useEffect(() => {
    if (record.state.status !== 'success') return
    setRecordOpen(false)
    setRecordProductId(null)
    setVariantId(null)
    setLocationId(null)
    setDelta(0)
    setNote('')
    movementsCache.clear()
    balancesCache.clear()
    alertsCache.clear()
    loadMovements()
    loadBalances()
    loadActiveAlerts()
    toast.show(t('bimeStockPage.recorded'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [record.state])

  const balances = useApiCall<StockBalanceResponse[]>()
  const [balFilterProduct, setBalFilterProduct] = useState<string | null>(null)
  const [balFilterVariant, setBalFilterVariant] = useState<string | null>(null)
  const [balFilterLocation, setBalFilterLocation] = useState<string | null>(null)

  function loadBalances() {
    balances.call(() => balancesCache.get(filterCacheKey(balFilterVariant, balFilterLocation), () => bime.stock.listBalances(token, {
      variantId: balFilterVariant ?? undefined,
      locationId: balFilterLocation ?? undefined,
    })))
  }
  useDebouncedEffect(loadBalances, [balFilterVariant, balFilterLocation], FILTER_DEBOUNCE_MS)

  // ── Alert thresholds ──
  const thresholds = useApiCall<StockAlertThresholdResponse[]>()
  const [thrFilterProduct, setThrFilterProduct] = useState<string | null>(null)
  const [thrFilterVariant, setThrFilterVariant] = useState<string | null>(null)
  const [thrFilterLocation, setThrFilterLocation] = useState<string | null>(null)

  function loadThresholds() {
    thresholds.call(() => thresholdsCache.get(filterCacheKey(thrFilterVariant, thrFilterLocation), () => bime.stock.listAlertThresholds(token, {
      variantId: thrFilterVariant ?? undefined,
      locationId: thrFilterLocation ?? undefined,
    })))
  }
  useDebouncedEffect(loadThresholds, [thrFilterVariant, thrFilterLocation], FILTER_DEBOUNCE_MS)

  const [thresholdModalOpen, setThresholdModalOpen] = useState(false)
  const [editingThreshold, setEditingThreshold] = useState<StockAlertThresholdResponse | null>(null)
  const [thresholdProductId, setThresholdProductId] = useState<string | null>(null)
  const [thresholdForm, setThresholdForm] = useState<StockAlertThresholdRequest>({ variantId: '', locationId: '', threshold: 0 })
  const saveThreshold = useApiCall<StockAlertThresholdResponse>()
  const deleteThreshold = useApiCall<void>()

  useEffect(() => {
    if (saveThreshold.state.status !== 'success') return
    setThresholdModalOpen(false)
    thresholdsCache.clear()
    alertsCache.clear()
    loadThresholds()
    loadActiveAlerts()
    toast.show(t('bimeStockPage.thresholdSaved'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [saveThreshold.state])

  function openCreateThreshold() {
    setEditingThreshold(null)
    setThresholdProductId(null)
    setThresholdForm({ variantId: '', locationId: '', threshold: 0 })
    setThresholdModalOpen(true)
  }

  function openEditThreshold(th: StockAlertThresholdResponse) {
    setEditingThreshold(th)
    setThresholdProductId(variantLookup[th.variantId]?.productId ?? null)
    setThresholdForm({ variantId: th.variantId, locationId: th.locationId, threshold: th.threshold })
    setThresholdModalOpen(true)
  }

  function removeThreshold(th: StockAlertThresholdResponse) {
    if (!window.confirm(t('bimeStockPage.thresholdDeleteConfirm', { variant: variantLabel(th.variantId), location: locationLabel(th.locationId) }))) return
    deleteThreshold.call(() => bime.stock.deleteAlertThreshold(th.variantId, th.locationId, token)).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
      thresholdsCache.clear()
      alertsCache.clear()
      loadThresholds()
      loadActiveAlerts()
      toast.show(t('bimeStockPage.thresholdDeleted'))
    })
  }

  // ── Active alerts ──
  const activeAlerts = useApiCall<StockAlertResponse[]>()
  const [alertFilterProduct, setAlertFilterProduct] = useState<string | null>(null)
  const [alertFilterVariant, setAlertFilterVariant] = useState<string | null>(null)
  const [alertFilterLocation, setAlertFilterLocation] = useState<string | null>(null)

  function loadActiveAlerts() {
    activeAlerts.call(() => alertsCache.get(filterCacheKey(alertFilterVariant, alertFilterLocation), () => bime.stock.listActiveAlerts(token, {
      variantId: alertFilterVariant ?? undefined,
      locationId: alertFilterLocation ?? undefined,
    })))
  }
  useDebouncedEffect(loadActiveAlerts, [alertFilterVariant, alertFilterLocation], FILTER_DEBOUNCE_MS)

  const movementColumns: Column<StockMovementResponse>[] = [
    { key: 'type', header: t('bimeStockPage.type'), render: m => <span className="role-badge">{t(`bimeStockPage.movementTypes.${m.movementType}`)}</span> },
    { key: 'delta', header: t('bimeStockPage.delta'), render: m => <span className={m.delta < 0 ? 'feedback-error' : 'feedback-success'}>{m.delta > 0 ? `+${m.delta}` : m.delta}</span> },
    { key: 'variant', header: t('bimeStockPage.variant'), render: m => variantLabel(m.variantId) },
    { key: 'location', header: t('bimeStockPage.location'), render: m => locationLabel(m.locationId) },
    { key: 'note', header: t('bimeStockPage.note'), render: m => <span className="td-muted">{m.note ?? '—'}</span> },
    { key: 'created', header: t('bimeStockPage.created'), render: m => <span className="td-muted">{new Date(m.createdAt).toLocaleString()}</span> },
  ]

  const balanceColumns: Column<StockBalanceResponse>[] = [
    { key: 'variant', header: t('bimeStockPage.variant'), render: b => variantLabel(b.variantId) },
    { key: 'location', header: t('bimeStockPage.location'), render: b => locationLabel(b.locationId) },
    { key: 'quantity', header: t('bimeStockPage.quantity'), render: b => b.quantity },
    { key: 'modified', header: t('bimeStockPage.modified'), render: b => <span className="td-muted">{new Date(b.modifiedAt).toLocaleString()}</span> },
  ]

  const thresholdColumns: Column<StockAlertThresholdResponse>[] = [
    { key: 'variant', header: t('bimeStockPage.variant'), render: th => variantLabel(th.variantId) },
    { key: 'location', header: t('bimeStockPage.location'), render: th => locationLabel(th.locationId) },
    { key: 'threshold', header: t('bimeStockPage.threshold'), render: th => th.threshold },
    { key: 'modified', header: t('bimeStockPage.modified'), render: th => <span className="td-muted">{new Date(th.modifiedAt).toLocaleString()}</span> },
    ...(permissions.canManageBime ? [{
      key: 'actions',
      header: '',
      render: (th: StockAlertThresholdResponse) => (
        <RowActionsMenu actions={[
          { label: t('common.actions.edit'), onClick: () => openEditThreshold(th) },
          { label: t('common.actions.delete'), onClick: () => removeThreshold(th), danger: true },
        ]} />
      ),
    }] : []),
  ]

  const activeAlertColumns: Column<StockAlertResponse>[] = [
    { key: 'variant', header: t('bimeStockPage.variant'), render: a => variantLabel(a.variantId) },
    { key: 'location', header: t('bimeStockPage.location'), render: a => locationLabel(a.locationId) },
    { key: 'quantity', header: t('bimeStockPage.quantity'), render: a => <span className="feedback-error">{a.quantity}</span> },
    { key: 'threshold', header: t('bimeStockPage.threshold'), render: a => a.threshold },
    { key: 'triggeredAt', header: t('bimeStockPage.triggeredAt'), render: a => <span className="td-muted">{new Date(a.triggeredAt).toLocaleString()}</span> },
  ]

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('bimeStockPage.title')}</h1>
          <p>{t('bimeStockPage.subtitle')}</p>
        </div>
      </div>

      <Tabs
        tabs={[
          { id: 'movements', label: t('bimeStockPage.tabMovements') },
          { id: 'balances', label: t('bimeStockPage.tabBalances') },
          { id: 'thresholds', label: t('bimeStockPage.tabThresholds') },
          { id: 'alerts', label: t('bimeStockPage.tabAlerts') },
        ]}
        active={activeTab}
        onChange={setActiveTab}
      >
        {activeTab === 'movements' && (
          <div className="panel">
            <div className="fields">
              <div className="field">
                <label>{t('bimeStockPage.filterProduct')}</label>
                <Combobox items={productItems} value={movFilterProduct} onChange={id => { setMovFilterProduct(id); setMovFilterVariant(null) }} placeholder={t('bimeStockPage.allProducts')} />
              </div>
              <div className="field">
                <label>{t('bimeStockPage.filterVariant')}</label>
                <Combobox items={movFilterProduct ? variantItemsFor(movFilterProduct) : []} value={movFilterVariant} onChange={setMovFilterVariant} placeholder={t('bimeStockPage.allVariants')} disabled={!movFilterProduct} />
              </div>
              <div className="field">
                <label>{t('bimeStockPage.filterLocation')}</label>
                <Combobox items={locationItems} value={movFilterLocation} onChange={setMovFilterLocation} placeholder={t('bimeStockPage.allLocations')} />
              </div>
            </div>
            {movements.state.status === 'error' && <Feedback state={movements.state} />}
            <DataTable
              columns={movementColumns}
              rows={filterByProduct(movements.state.status === 'success' ? movements.state.data : [], movFilterProduct)}
              rowKey={m => m.id}
              emptyLabel={t('bimeStockPage.movementsEmptyState')}
              headerAction={
                <div style={{ display: 'flex', gap: 8 }}>
                  <button className="btn btn-outline" onClick={loadMovements} type="button">{t('common.actions.refresh')}</button>
                  {permissions.canManageBime && (
                    <button className="btn btn-primary" onClick={() => setRecordOpen(true)} type="button">{t('bimeStockPage.recordAction')}</button>
                  )}
                </div>
              }
            />
          </div>
        )}

        {activeTab === 'balances' && (
          <div className="panel">
            <div className="fields">
              <div className="field">
                <label>{t('bimeStockPage.filterProduct')}</label>
                <Combobox items={productItems} value={balFilterProduct} onChange={id => { setBalFilterProduct(id); setBalFilterVariant(null) }} placeholder={t('bimeStockPage.allProducts')} />
              </div>
              <div className="field">
                <label>{t('bimeStockPage.filterVariant')}</label>
                <Combobox items={balFilterProduct ? variantItemsFor(balFilterProduct) : []} value={balFilterVariant} onChange={setBalFilterVariant} placeholder={t('bimeStockPage.allVariants')} disabled={!balFilterProduct} />
              </div>
              <div className="field">
                <label>{t('bimeStockPage.filterLocation')}</label>
                <Combobox items={locationItems} value={balFilterLocation} onChange={setBalFilterLocation} placeholder={t('bimeStockPage.allLocations')} />
              </div>
            </div>
            {balances.state.status === 'error' && <Feedback state={balances.state} />}
            <DataTable
              columns={balanceColumns}
              rows={filterByProduct(balances.state.status === 'success' ? balances.state.data : [], balFilterProduct)}
              rowKey={b => `${b.variantId}-${b.locationId}`}
              emptyLabel={t('bimeStockPage.balancesEmptyState')}
              headerAction={<button className="btn btn-outline" onClick={loadBalances} type="button">{t('common.actions.refresh')}</button>}
            />
          </div>
        )}

        {activeTab === 'thresholds' && (
          <div className="panel">
            <p className="panel-hint">{t('bimeStockPage.thresholdsHint')}</p>
            <div className="fields">
              <div className="field">
                <label>{t('bimeStockPage.filterProduct')}</label>
                <Combobox items={productItems} value={thrFilterProduct} onChange={id => { setThrFilterProduct(id); setThrFilterVariant(null) }} placeholder={t('bimeStockPage.allProducts')} />
              </div>
              <div className="field">
                <label>{t('bimeStockPage.filterVariant')}</label>
                <Combobox items={thrFilterProduct ? variantItemsFor(thrFilterProduct) : []} value={thrFilterVariant} onChange={setThrFilterVariant} placeholder={t('bimeStockPage.allVariants')} disabled={!thrFilterProduct} />
              </div>
              <div className="field">
                <label>{t('bimeStockPage.filterLocation')}</label>
                <Combobox items={locationItems} value={thrFilterLocation} onChange={setThrFilterLocation} placeholder={t('bimeStockPage.allLocations')} />
              </div>
            </div>
            {thresholds.state.status === 'error' && <Feedback state={thresholds.state} />}
            <DataTable
              columns={thresholdColumns}
              rows={filterByProduct(thresholds.state.status === 'success' ? thresholds.state.data : [], thrFilterProduct)}
              rowKey={th => `${th.variantId}-${th.locationId}`}
              emptyLabel={t('bimeStockPage.thresholdsEmptyState')}
              headerAction={
                <div style={{ display: 'flex', gap: 8 }}>
                  <button className="btn btn-outline" onClick={loadThresholds} type="button">{t('common.actions.refresh')}</button>
                  {permissions.canManageBime && (
                    <button className="btn btn-primary" onClick={openCreateThreshold} type="button">{t('bimeStockPage.setThresholdAction')}</button>
                  )}
                </div>
              }
            />
          </div>
        )}

        {activeTab === 'alerts' && (
          <div className="panel">
            <p className="panel-hint">{t('bimeStockPage.alertsHint')}</p>
            <div className="fields">
              <div className="field">
                <label>{t('bimeStockPage.filterProduct')}</label>
                <Combobox items={productItems} value={alertFilterProduct} onChange={id => { setAlertFilterProduct(id); setAlertFilterVariant(null) }} placeholder={t('bimeStockPage.allProducts')} />
              </div>
              <div className="field">
                <label>{t('bimeStockPage.filterVariant')}</label>
                <Combobox items={alertFilterProduct ? variantItemsFor(alertFilterProduct) : []} value={alertFilterVariant} onChange={setAlertFilterVariant} placeholder={t('bimeStockPage.allVariants')} disabled={!alertFilterProduct} />
              </div>
              <div className="field">
                <label>{t('bimeStockPage.filterLocation')}</label>
                <Combobox items={locationItems} value={alertFilterLocation} onChange={setAlertFilterLocation} placeholder={t('bimeStockPage.allLocations')} />
              </div>
            </div>
            {activeAlerts.state.status === 'error' && <Feedback state={activeAlerts.state} />}
            <DataTable
              columns={activeAlertColumns}
              rows={filterByProduct(activeAlerts.state.status === 'success' ? activeAlerts.state.data : [], alertFilterProduct)}
              rowKey={a => `${a.variantId}-${a.locationId}`}
              emptyLabel={t('bimeStockPage.alertsEmptyState')}
              headerAction={<button className="btn btn-outline" onClick={loadActiveAlerts} type="button">{t('common.actions.refresh')}</button>}
            />
          </div>
        )}
      </Tabs>

      <Modal open={recordOpen} onClose={() => setRecordOpen(false)} title={t('bimeStockPage.recordTitle')}>
        <p className="panel-hint">{t('bimeStockPage.recordHint')}</p>
        <div className="fields">
          <div className="field">
            <label>{t('bimeStockPage.product')}</label>
            <Combobox items={productItems} value={recordProductId} onChange={id => { setRecordProductId(id); setVariantId(null) }} placeholder={t('bimeStockPage.productPlaceholder')} />
          </div>
          <div className="field">
            <label>{t('bimeStockPage.variant')}</label>
            <Combobox items={recordProductId ? variantItemsFor(recordProductId) : []} value={variantId} onChange={setVariantId} placeholder={t('bimeStockPage.variantPlaceholder')} disabled={!recordProductId} />
          </div>
          <div className="field">
            <label>{t('bimeStockPage.location')}</label>
            <Combobox items={locationItems} value={locationId} onChange={setLocationId} placeholder={t('bimeStockPage.locationPlaceholder')} />
          </div>
          <div className="field">
            <label>{t('bimeStockPage.movementType')}</label>
            <select value={movementType} onChange={e => setMovementType(e.target.value as MovementType)}>
              {MOVEMENT_TYPES.map(mt => <option key={mt} value={mt}>{t(`bimeStockPage.movementTypes.${mt}`)}</option>)}
            </select>
          </div>
          <div className="field">
            <label>{t('bimeStockPage.delta')}</label>
            <input type="number" value={delta} onChange={e => setDelta(parseInt(e.target.value, 10) || 0)} />
          </div>
          <div className="field">
            <label>{t('bimeStockPage.note')}</label>
            <input value={note} onChange={e => setNote(e.target.value)} placeholder={t('bimeStockPage.notePlaceholder')} />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={record.state.status === 'loading' || !variantId || !locationId || delta === 0}
            onClick={() => variantId && locationId && record.call(() => bime.stock.recordMovement(
              { variantId, locationId, movementType, delta, note: note.trim() || undefined }, token,
            ))}
          >
            {record.state.status === 'loading' ? t('common.actions.loading') : t('common.actions.create')}
          </button>
        </div>
        {record.state.status === 'error' && <Feedback state={record.state} />}
      </Modal>

      <Modal open={thresholdModalOpen} onClose={() => setThresholdModalOpen(false)} title={t(editingThreshold ? 'bimeStockPage.editThresholdTitle' : 'bimeStockPage.setThresholdTitle')}>
        <p className="panel-hint">{t('bimeStockPage.thresholdHint')}</p>
        <div className="fields">
          <div className="field">
            <label>{t('bimeStockPage.product')}</label>
            <Combobox
              items={productItems}
              value={thresholdProductId}
              onChange={id => { setThresholdProductId(id); setThresholdForm(f => ({ ...f, variantId: '' })) }}
              placeholder={t('bimeStockPage.productPlaceholder')}
              disabled={!!editingThreshold}
            />
          </div>
          <div className="field">
            <label>{t('bimeStockPage.variant')}</label>
            <Combobox
              items={thresholdProductId ? variantItemsFor(thresholdProductId) : []}
              value={thresholdForm.variantId || null}
              onChange={id => setThresholdForm(f => ({ ...f, variantId: id ?? '' }))}
              placeholder={t('bimeStockPage.variantPlaceholder')}
              disabled={!thresholdProductId || !!editingThreshold}
            />
          </div>
          <div className="field">
            <label>{t('bimeStockPage.location')}</label>
            <Combobox
              items={locationItems}
              value={thresholdForm.locationId || null}
              onChange={id => setThresholdForm(f => ({ ...f, locationId: id ?? '' }))}
              placeholder={t('bimeStockPage.locationPlaceholder')}
              disabled={!!editingThreshold}
            />
          </div>
          <div className="field">
            <label>{t('bimeStockPage.threshold')}</label>
            <input
              type="number"
              min={0}
              value={thresholdForm.threshold}
              onChange={e => setThresholdForm(f => ({ ...f, threshold: parseInt(e.target.value, 10) || 0 }))}
            />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={saveThreshold.state.status === 'loading' || !thresholdForm.variantId || !thresholdForm.locationId}
            onClick={() => saveThreshold.call(() => bime.stock.setAlertThreshold(thresholdForm, token))}
          >
            {saveThreshold.state.status === 'loading' ? t('common.actions.loading') : t(editingThreshold ? 'common.actions.save' : 'common.actions.create')}
          </button>
        </div>
        {saveThreshold.state.status === 'error' && <Feedback state={saveThreshold.state} />}
      </Modal>
    </div>
  )
}
