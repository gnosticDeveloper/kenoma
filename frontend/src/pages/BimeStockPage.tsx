import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { bime } from '../api/bime'
import { createCache } from '../lib/cache'
import { formatQuantity } from '../lib/uom'
import { useApiCall } from '../hooks/useApiCall'
import { useDebouncedEffect } from '../hooks/useDebouncedEffect'
import { useToast } from '../components/Toast'
import { Modal } from '../components/Modal'
import { Tabs } from '../components/Tabs'
import { DataTable, type Column } from '../components/DataTable'
import { Combobox } from '../components/Combobox'
import { Feedback } from '../components/Feedback'
import { FilterChips, FilterDisclosure, toggleOptionId } from '../components/OptionFilter'
import { SearchIcon } from '../components/icons'
import type { Permissions } from '../auth'
import { RowActionsMenu } from '../components/RowActionsMenu'
import { Gs1Help } from '../components/Gs1Explainer'
import { InfoTip } from '../components/InfoTip'
import BimeTransfersTab from './BimeTransfersTab'
import BimeBatchesTab from './BimeBatchesTab'
import type {
  BarcodeLookupResponse,
  BatchResponse,
  LocationResponse,
  MovementType,
  ProductMetadataResponse,
  ProductResponse,
  ProductVariantResponse,
  StockAlertResponse,
  StockAlertThresholdRequest,
  StockAlertThresholdResponse,
  StockBalanceResponse,
  StockMovementResponse,
  UomConversionResponse,
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

function filterCacheKey(variantId: string | null, locationId: string | null, optionIds: string[], matchAll: boolean): string {
  return `${variantId ?? ''}|${locationId ?? ''}|${[...optionIds].sort().join(',')}|${matchAll}`
}

type VariantInfo = {
  productId: string
  sku: string | null
  productName: string
  optionsLabel: string
  baseUom: string
  uomConversions: UomConversionResponse[]
}

export default function BimeStockPage({ token, permissions }: Props) {
  const { t } = useTranslation()
  const toast = useToast()
  const [activeTab, setActiveTab] = useState('movements')

  const locations = useApiCall<LocationResponse[]>()
  const products = useApiCall<ProductResponse[]>()
  const metadataDefs = useApiCall<ProductMetadataResponse[]>()
  const [variantsByProduct, setVariantsByProduct] = useState<Record<string, ProductVariantResponse[]>>({})
  const [variantLookup, setVariantLookup] = useState<Record<string, VariantInfo>>({})

  useEffect(() => {
    locations.call(() => bime.locations.list(token))
    products.call(() => bime.products.list(token))
    metadataDefs.call(() => bime.metadata.list(token))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const metadataDefsList = metadataDefs.state.status === 'success' ? metadataDefs.state.data : []

  const [optionFilter, setOptionFilter] = useState<string[]>([])
  const [optionMatchAll, setOptionMatchAll] = useState(false)
  function toggleOptionFilter(optionId: string) {
    setOptionFilter(prev => toggleOptionId(prev, optionId))
  }

  useEffect(() => {
    if (products.state.status !== 'success') return
    let cancelled = false
    Promise.allSettled(products.state.data.map(p => bime.products.get(p.id, token))).then(results => {
      if (cancelled) return
      const byProduct: Record<string, ProductVariantResponse[]> = {}
      const lookup: Record<string, VariantInfo> = {}
      results.forEach(r => {
        if (r.status !== 'fulfilled') return
        const p = r.value
        const vs = p.variants ?? []
        byProduct[p.id] = vs
        vs.forEach(v => { lookup[v.id] = { productId: p.id, sku: v.sku, productName: p.name, optionsLabel: v.options.map(o => o.value).join(', '), baseUom: v.baseUom, uomConversions: v.uomConversions } })
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

  function variantQuantityLabel(variantId: string, quantity: number): string {
    const info = variantLookup[variantId]
    if (!info) return String(quantity)
    return formatQuantity(quantity, info.baseUom, info.uomConversions)
  }

  function variantUnits(variantId: string): { base: string; alts: string[] } {
    const info = variantLookup[variantId]
    return {
      base: info?.baseUom ?? '',
      alts: (info?.uomConversions ?? []).map(c => c.uomName),
    }
  }

  // ── Record movement ──
  const [recordOpen, setRecordOpen] = useState(false)
  const [recordProductId, setRecordProductId] = useState<string | null>(null)
  const [variantId, setVariantId] = useState<string | null>(null)
  const [locationId, setLocationId] = useState<string | null>(null)
  const [movementType, setMovementType] = useState<MovementType>('INBOUND')
  const [delta, setDelta] = useState(0)
  const [movementUom, setMovementUom] = useState<string | null>(null)
  const [note, setNote] = useState('')
  const [batchCode, setBatchCode] = useState('')
  const [batchExpiry, setBatchExpiry] = useState('')
  const [batchGs1, setBatchGs1] = useState('')
  const [outboundBatchId, setOutboundBatchId] = useState<string | null>(null)
  const record = useApiCall<StockMovementResponse>()
  const outboundBatches = useApiCall<BatchResponse[]>()
  const movementUomConversions = variantId ? (variantLookup[variantId]?.uomConversions ?? []) : []

  function productTracksBatches(productId: string | null): boolean {
    if (!productId || products.state.status !== 'success') return false
    return products.state.data.find(p => p.id === productId)?.tracksBatches ?? false
  }
  const recordTracksBatches = productTracksBatches(recordProductId)
  const recordIsInbound = movementType === 'INBOUND' || (movementType === 'ADJUSTMENT' && delta > 0)

  useEffect(() => {
    setMovementUom(null)
  }, [variantId])

  useEffect(() => {
    setOutboundBatchId(null)
    if (recordOpen && recordTracksBatches && !recordIsInbound && variantId && locationId) {
      outboundBatches.call(() => bime.batches.list(token, { variantId, locationId, status: 'ACTIVE' }))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recordOpen, recordTracksBatches, recordIsInbound, variantId, locationId])

  const movements = useApiCall<StockMovementResponse[]>()
  const [movFilterProduct, setMovFilterProduct] = useState<string | null>(null)
  const [movFilterVariant, setMovFilterVariant] = useState<string | null>(null)
  const [movFilterLocation, setMovFilterLocation] = useState<string | null>(null)

  function loadMovements() {
    movements.call(() => movementsCache.get(filterCacheKey(movFilterVariant, movFilterLocation, optionFilter, optionMatchAll), () => bime.stock.listMovements(token, {
      variantId: movFilterVariant ?? undefined,
      locationId: movFilterLocation ?? undefined,
      optionIds: optionFilter.length ? optionFilter : undefined,
      matchAll: optionMatchAll,
    })))
  }
  useDebouncedEffect(loadMovements, [movFilterVariant, movFilterLocation, optionFilter, optionMatchAll], FILTER_DEBOUNCE_MS)

  useEffect(() => {
    if (record.state.status !== 'success') return
    setRecordOpen(false)
    setRecordProductId(null)
    setVariantId(null)
    setLocationId(null)
    setDelta(0)
    setNote('')
    setBatchCode('')
    setBatchExpiry('')
    setBatchGs1('')
    setOutboundBatchId(null)
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
    balances.call(() => balancesCache.get(filterCacheKey(balFilterVariant, balFilterLocation, optionFilter, optionMatchAll), () => bime.stock.listBalances(token, {
      variantId: balFilterVariant ?? undefined,
      locationId: balFilterLocation ?? undefined,
      optionIds: optionFilter.length ? optionFilter : undefined,
      matchAll: optionMatchAll,
    })))
  }
  useDebouncedEffect(loadBalances, [balFilterVariant, balFilterLocation, optionFilter, optionMatchAll], FILTER_DEBOUNCE_MS)

  // ── Alert thresholds ──
  const thresholds = useApiCall<StockAlertThresholdResponse[]>()
  const [thrFilterProduct, setThrFilterProduct] = useState<string | null>(null)
  const [thrFilterVariant, setThrFilterVariant] = useState<string | null>(null)
  const [thrFilterLocation, setThrFilterLocation] = useState<string | null>(null)

  function loadThresholds() {
    thresholds.call(() => thresholdsCache.get(filterCacheKey(thrFilterVariant, thrFilterLocation, optionFilter, optionMatchAll), () => bime.stock.listAlertThresholds(token, {
      variantId: thrFilterVariant ?? undefined,
      locationId: thrFilterLocation ?? undefined,
      optionIds: optionFilter.length ? optionFilter : undefined,
      matchAll: optionMatchAll,
    })))
  }
  useDebouncedEffect(loadThresholds, [thrFilterVariant, thrFilterLocation, optionFilter, optionMatchAll], FILTER_DEBOUNCE_MS)

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
    activeAlerts.call(() => alertsCache.get(filterCacheKey(alertFilterVariant, alertFilterLocation, optionFilter, optionMatchAll), () => bime.stock.listActiveAlerts(token, {
      variantId: alertFilterVariant ?? undefined,
      locationId: alertFilterLocation ?? undefined,
      optionIds: optionFilter.length ? optionFilter : undefined,
      matchAll: optionMatchAll,
    })))
  }
  useDebouncedEffect(loadActiveAlerts, [alertFilterVariant, alertFilterLocation, optionFilter, optionMatchAll], FILTER_DEBOUNCE_MS)

  // ── Scan to locate: barcode -> which locations hold it ──
  const [locateValue, setLocateValue] = useState('')
  const [locateHit, setLocateHit] = useState<BarcodeLookupResponse | null>(null)
  const locateLookup = useApiCall<BarcodeLookupResponse>()
  const locateInputRef = useRef<HTMLInputElement>(null)

  function submitLocate(raw?: string) {
    const value = (raw ?? locateValue).trim()
    if (!value) return
    locateLookup.call(() => bime.barcodes.lookup(value, token)).then(r => {
      if (!r.ok) { setLocateHit(null); toast.show(r.message, 'error') }
    })
  }

  useEffect(() => {
    if (locateLookup.state.status !== 'success') return
    const hit = locateLookup.state.data
    setLocateHit(hit)
    setBalFilterProduct(hit.productId); setBalFilterVariant(hit.variant.id)
    setMovFilterProduct(hit.productId); setMovFilterVariant(hit.variant.id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locateLookup.state])

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.metaKey || e.ctrlKey || e.altKey) return
      if (document.querySelector('.modal-overlay')) return
      const el = e.target as HTMLElement | null
      if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable)) return
      if (e.key === 'Enter') {
        const cur = locateInputRef.current?.value.trim()
        if (cur) { e.preventDefault(); submitLocate(cur) }
        return
      }
      if (e.key === 'Backspace') {
        e.preventDefault(); setLocateValue(v => v.slice(0, -1)); locateInputRef.current?.focus(); return
      }
      if (e.key.length === 1) {
        e.preventDefault(); setLocateValue(v => v + e.key); locateInputRef.current?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const locateTotal = locateHit ? locateHit.variant.stock.reduce((n, s) => n + s.quantity, 0) : 0

  const movementColumns: Column<StockMovementResponse>[] = [
    { key: 'type', header: t('bimeStockPage.type'), render: m => <span className="role-badge">{t(`bimeStockPage.movementTypes.${m.movementType}`)}</span> },
    {
      key: 'delta',
      header: t('bimeStockPage.delta'),
      render: m => (
        <span className={m.delta < 0 ? 'feedback-error' : 'feedback-success'}>
          {m.uom
            ? `${m.uomQuantity! > 0 ? '+' : ''}${m.uomQuantity} ${m.uom}`
            : `${m.delta > 0 ? '+' : ''}${variantQuantityLabel(m.variantId, m.delta)}`}
          {m.uom && <span className="td-muted"> ({variantQuantityLabel(m.variantId, m.delta)})</span>}
        </span>
      ),
    },
    { key: 'variant', header: t('bimeStockPage.variant'), render: m => variantLabel(m.variantId) },
    { key: 'location', header: t('bimeStockPage.location'), render: m => locationLabel(m.locationId) },
    { key: 'note', header: t('bimeStockPage.note'), render: m => <span className="td-muted">{m.note ?? '—'}</span> },
    { key: 'created', header: t('bimeStockPage.created'), render: m => <span className="td-muted">{new Date(m.createdAt).toLocaleString()}</span> },
  ]

  const balanceColumns: Column<StockBalanceResponse>[] = [
    { key: 'variant', header: t('bimeStockPage.variant'), render: b => variantLabel(b.variantId) },
    { key: 'location', header: t('bimeStockPage.location'), render: b => locationLabel(b.locationId) },
    { key: 'quantity', header: t('bimeStockPage.quantity'), render: b => variantQuantityLabel(b.variantId, b.quantity) },
    { key: 'modified', header: t('bimeStockPage.modified'), render: b => <span className="td-muted">{new Date(b.modifiedAt).toLocaleString()}</span> },
  ]

  const thresholdColumns: Column<StockAlertThresholdResponse>[] = [
    { key: 'variant', header: t('bimeStockPage.variant'), render: th => variantLabel(th.variantId) },
    { key: 'location', header: t('bimeStockPage.location'), render: th => locationLabel(th.locationId) },
    { key: 'threshold', header: t('bimeStockPage.threshold'), render: th => variantQuantityLabel(th.variantId, th.threshold) },
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
    { key: 'quantity', header: t('bimeStockPage.quantity'), render: a => <span className="feedback-error">{variantQuantityLabel(a.variantId, a.quantity)}</span> },
    { key: 'threshold', header: t('bimeStockPage.threshold'), render: a => variantQuantityLabel(a.variantId, a.threshold) },
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

      <FilterDisclosure activeCount={optionFilter.length}>
        <FilterChips
          metadataDefs={metadataDefsList}
          selectedOptionIds={optionFilter}
          onToggle={toggleOptionFilter}
          onClear={() => setOptionFilter([])}
          matchAll={optionMatchAll}
          onMatchAllChange={setOptionMatchAll}
        />
      </FilterDisclosure>

      <div className="barcode-lookup">
        <div className="barcode-lookup-head">
          <span>{t('bimeStockPage.locateLabel')}</span>
        </div>
        <div className="barcode-lookup-bar">
          <div className="barcode-lookup-field">
            <SearchIcon className="barcode-lookup-icon" />
            <input
              ref={locateInputRef}
              value={locateValue}
              onChange={e => setLocateValue(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); submitLocate() } }}
              placeholder={t('bimeStockPage.locatePlaceholder')}
            />
            {locateValue && (
              <button
                type="button"
                className="barcode-lookup-clear"
                aria-label={t('common.actions.clear')}
                onClick={() => { setLocateValue(''); setLocateHit(null) }}
              >
                ×
              </button>
            )}
          </div>
          <button className="btn btn-primary btn-sm" type="button" onClick={() => submitLocate()} disabled={!locateValue.trim()}>
            {t('bimeStockPage.locateAction')}
          </button>
        </div>
        {locateHit && (() => {
          const factor = locateHit.factor && locateHit.factor > 1 ? locateHit.factor : 1
          const isPack = factor > 1
          const unit = isPack ? locateHit.uom : locateHit.variant.baseUom
          const inUnit = (q: number) => `${(q / factor).toLocaleString(undefined, { maximumFractionDigits: 2 })} ${unit}`
          const inBase = (q: number) => `${q.toLocaleString()} ${locateHit.variant.baseUom}`
          const stock = [...locateHit.variant.stock].sort((a, b) => b.quantity - a.quantity)
          return (
            <div className="stock-locate-result">
              <div className="stock-locate-head">
                <span className="stock-locate-name">
                  {locateHit.productName}
                  {!locateHit.variant.isActive && (
                    <span className="barcode-lookup-retired">{t('bimeStockPage.locateRetired')}</span>
                  )}
                </span>
                <span className="stock-locate-sub">
                  {locateHit.variant.sku ?? locateHit.productSku}
                  {isPack && ` · ${locateHit.uom} ×${locateHit.factor}`}
                </span>
              </div>
              {stock.length === 0
                ? <p className="td-muted stock-locate-empty">{t('bimeStockPage.locateNoStock')}</p>
                : (
                  <ul className="stock-locate-list">
                    {stock.map(s => (
                      <li key={s.locationId}>
                        <span className="stock-locate-loc">{locationLabel(s.locationId)}</span>
                        <span className="stock-locate-qty">
                          {inUnit(s.quantity)}
                          {isPack && <span className="td-muted"> ({inBase(s.quantity)})</span>}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              <div className="stock-locate-total">
                {t('bimeStockPage.locateTotal', { qty: inUnit(locateTotal) })}
                {isPack && ` (${inBase(locateTotal)})`}
              </div>
            </div>
          )
        })()}
      </div>

      <Tabs
        tabs={[
          { id: 'movements', label: t('bimeStockPage.tabMovements') },
          { id: 'balances', label: t('bimeStockPage.tabBalances') },
          { id: 'transfers', label: t('bimeStockPage.tabTransfers') },
          { id: 'batches', label: t('bimeStockPage.tabBatches') },
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

        {activeTab === 'transfers' && (
          <BimeTransfersTab
            token={token}
            canManage={permissions.canManageBime}
            canApprove={permissions.canApproveBimeTransfers}
            locationItems={locationItems}
            productItems={productItems}
            variantItemsFor={variantItemsFor}
            variantLabel={variantLabel}
            locationLabel={locationLabel}
            variantQuantityLabel={variantQuantityLabel}
            variantUnits={variantUnits}
            productForVariant={vid => variantLookup[vid]?.productId ?? null}
          />
        )}

        {activeTab === 'batches' && (
          <BimeBatchesTab
            token={token}
            canManage={permissions.canManageBime}
            canRecall={permissions.canRecallBimeBatches}
            productItems={productItems}
            locationItems={locationItems}
            variantItemsFor={variantItemsFor}
            variantLabel={variantLabel}
            locationLabel={locationLabel}
          />
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
            <input type="number" step="any" value={delta} onChange={e => setDelta(parseFloat(e.target.value) || 0)} />
          </div>
          {movementUomConversions.length > 0 && (
            <div className="field">
              <label>{t('bimeStockPage.unit')}</label>
              <select value={movementUom ?? ''} onChange={e => setMovementUom(e.target.value || null)}>
                <option value="">{variantId ? variantLookup[variantId]?.baseUom : t('bimeStockPage.unit')}</option>
                {movementUomConversions.map(c => <option key={c.uomName} value={c.uomName}>{c.uomName}</option>)}
              </select>
            </div>
          )}
          {recordTracksBatches && recordIsInbound && (
            <>
              <div className="field">
                <label>
                  {t('bimeStockPage.batchGs1')}
                  <InfoTip label={t('bimeStockPage.batchGs1Explain')}><Gs1Help /></InfoTip>
                </label>
                <input value={batchGs1} onChange={e => setBatchGs1(e.target.value)} placeholder={t('bimeStockPage.batchGs1Placeholder')} />
              </div>
              <div className="field">
                <label>{t('bimeStockPage.batchCode')}</label>
                <input value={batchCode} onChange={e => setBatchCode(e.target.value)} placeholder="LOT-2026-08-A" disabled={!!batchGs1.trim()} />
              </div>
              <div className="field">
                <label>{t('bimeStockPage.batchExpiry')}</label>
                <input type="date" value={batchExpiry} onChange={e => setBatchExpiry(e.target.value)} disabled={!!batchGs1.trim()} />
              </div>
            </>
          )}
          {recordTracksBatches && !recordIsInbound && (
            <div className="field">
              <label>{t('bimeStockPage.batchOutbound')}</label>
              <select value={outboundBatchId ?? ''} onChange={e => setOutboundBatchId(e.target.value || null)} disabled={!variantId || !locationId}>
                <option value="">{t('bimeStockPage.batchFefo')}</option>
                {(outboundBatches.state.status === 'success' ? outboundBatches.state.data : []).map(b => (
                  <option key={b.id} value={b.id}>
                    {b.batchCode}{b.expiryDate ? ` · ${b.expiryDate}` : ''} · {b.totalQuantity}
                  </option>
                ))}
              </select>
            </div>
          )}
          <div className="field">
            <label>{t('bimeStockPage.note')}</label>
            <input value={note} onChange={e => setNote(e.target.value)} placeholder={t('bimeStockPage.notePlaceholder')} />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={
              record.state.status === 'loading' || !variantId || !locationId || delta === 0
              || (recordTracksBatches && recordIsInbound && !batchGs1.trim() && !batchCode.trim())
            }
            onClick={() => variantId && locationId && record.call(() => bime.stock.recordMovement(
              {
                variantId, locationId, movementType, delta,
                uom: movementUom ?? undefined,
                note: note.trim() || undefined,
                ...(recordTracksBatches && recordIsInbound
                  ? {
                      gs1: batchGs1.trim() || undefined,
                      batchCode: batchGs1.trim() ? undefined : (batchCode.trim() || undefined),
                      expiryDate: batchGs1.trim() ? undefined : (batchExpiry || undefined),
                    }
                  : {}),
                ...(recordTracksBatches && !recordIsInbound && outboundBatchId ? { batchId: outboundBatchId } : {}),
              }, token,
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
              step="any"
              value={thresholdForm.threshold}
              onChange={e => setThresholdForm(f => ({ ...f, threshold: parseFloat(e.target.value) || 0 }))}
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
