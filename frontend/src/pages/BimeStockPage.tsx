import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { bime } from '../api/bime'
import { useApiCall } from '../hooks/useApiCall'
import { useToast } from '../components/Toast'
import { Modal } from '../components/Modal'
import { Tabs } from '../components/Tabs'
import { DataTable, type Column } from '../components/DataTable'
import { Combobox } from '../components/Combobox'
import { Feedback } from '../components/Feedback'
import type { Permissions } from '../auth'
import type {
  LocationResponse,
  MovementType,
  ProductResponse,
  ProductVariantResponse,
  StockBalanceResponse,
  StockMovementResponse,
} from '../types'

interface Props {
  token: string
  permissions: Permissions
}

const MOVEMENT_TYPES: MovementType[] = ['INBOUND', 'OUTBOUND', 'ADJUSTMENT']

type VariantInfo = { sku: string | null; productName: string; optionsLabel: string }

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
        vs.forEach(v => { lookup[v.id] = { sku: v.sku, productName: p.name, optionsLabel: v.options.map(o => o.value).join(', ') } })
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
    movements.call(() => bime.stock.listMovements(token, {
      variantId: movFilterVariant ?? undefined,
      locationId: movFilterLocation ?? undefined,
    }))
  }
  useEffect(loadMovements, [])

  useEffect(() => {
    if (record.state.status !== 'success') return
    setRecordOpen(false)
    setRecordProductId(null)
    setVariantId(null)
    setLocationId(null)
    setDelta(0)
    setNote('')
    loadMovements()
    toast.show(t('bimeStockPage.recorded'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [record.state])

  const balances = useApiCall<StockBalanceResponse[]>()
  const [balFilterProduct, setBalFilterProduct] = useState<string | null>(null)
  const [balFilterVariant, setBalFilterVariant] = useState<string | null>(null)
  const [balFilterLocation, setBalFilterLocation] = useState<string | null>(null)

  function loadBalances() {
    balances.call(() => bime.stock.listBalances(token, {
      variantId: balFilterVariant ?? undefined,
      locationId: balFilterLocation ?? undefined,
    }))
  }
  useEffect(loadBalances, [])

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
              rows={movements.state.status === 'success' ? movements.state.data : []}
              rowKey={m => m.id}
              emptyLabel={t('bimeStockPage.movementsEmptyState')}
              headerAction={
                <div style={{ display: 'flex', gap: 8 }}>
                  <button className="btn btn-outline" onClick={loadMovements} type="button">{t('common.actions.load')}</button>
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
              rows={balances.state.status === 'success' ? balances.state.data : []}
              rowKey={b => `${b.variantId}-${b.locationId}`}
              emptyLabel={t('bimeStockPage.balancesEmptyState')}
              headerAction={<button className="btn btn-outline" onClick={loadBalances} type="button">{t('common.actions.load')}</button>}
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
    </div>
  )
}
