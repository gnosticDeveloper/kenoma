import { useEffect, useState } from 'react'
import { bime } from '../api/bime'
import { useApiCall } from '../hooks/useApiCall'
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

type VariantLookup = Record<string, { sku: string | null; productName: string; optionsLabel: string }>
type LocationLookup = Record<string, LocationResponse>

function variantLabel(v: ProductVariantResponse): string {
  const opts = v.options.map(o => o.value).join(', ')
  const sku = v.sku ?? v.id.slice(0, 8) + '…'
  return opts ? `${sku} (${opts})` : sku
}

function VariantCell({ variantId, lookup }: { variantId: string; lookup: VariantLookup }) {
  const info = lookup[variantId]
  if (!info) return <span className="td-muted">{variantId.slice(0, 8)}…</span>
  return (
    <span>
      {info.productName} — <strong>{info.sku ?? variantId.slice(0, 8) + '…'}</strong>
      {info.optionsLabel && <span className="td-muted"> ({info.optionsLabel})</span>}
    </span>
  )
}

function LocationCell({ locationId, lookup }: { locationId: string; lookup: LocationLookup }) {
  const loc = lookup[locationId]
  if (!loc) return <span className="td-muted">{locationId.slice(0, 8)}…</span>
  return <span>{loc.name} <span className="td-muted">({loc.code})</span></span>
}

function MovementsTable({ movements, variantLookup, locationLookup }: {
  movements: StockMovementResponse[]; variantLookup: VariantLookup; locationLookup: LocationLookup
}) {
  if (movements.length === 0) {
    return <div className="empty-state">No movements found.</div>
  }
  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>Type</th>
          <th>Delta</th>
          <th>Variant</th>
          <th>Location</th>
          <th>Note</th>
          <th>Created</th>
        </tr>
      </thead>
      <tbody>
        {movements.map(m => (
          <tr key={m.id}>
            <td><span className="role-badge">{m.movementType}</span></td>
            <td className={m.delta < 0 ? 'error' : 'success'} style={{ padding: 0 }}>{m.delta > 0 ? `+${m.delta}` : m.delta}</td>
            <td><VariantCell variantId={m.variantId} lookup={variantLookup} /></td>
            <td><LocationCell locationId={m.locationId} lookup={locationLookup} /></td>
            <td className="td-muted">{m.note ?? '—'}</td>
            <td className="td-muted">{new Date(m.createdAt).toLocaleString()}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function BalancesTable({ balances, variantLookup, locationLookup }: {
  balances: StockBalanceResponse[]; variantLookup: VariantLookup; locationLookup: LocationLookup
}) {
  if (balances.length === 0) {
    return <div className="empty-state">No balances found.</div>
  }
  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>Variant</th>
          <th>Location</th>
          <th>Quantity</th>
          <th>Modified</th>
        </tr>
      </thead>
      <tbody>
        {balances.map(b => (
          <tr key={`${b.variantId}-${b.locationId}`}>
            <td><VariantCell variantId={b.variantId} lookup={variantLookup} /></td>
            <td><LocationCell locationId={b.locationId} lookup={locationLookup} /></td>
            <td>{b.quantity}</td>
            <td className="td-muted">{new Date(b.modifiedAt).toLocaleString()}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export default function BimeStockPage({ token, permissions }: Props) {
  // Reference data (locations, products + their variants) used to power dropdowns
  // and to translate raw IDs back into readable names in the tables below.
  const locations = useApiCall<LocationResponse[]>()
  const products = useApiCall<ProductResponse[]>()
  const [variantsByProduct, setVariantsByProduct] = useState<Record<string, ProductVariantResponse[]>>({})
  const [variantLookup, setVariantLookup] = useState<VariantLookup>({})

  useEffect(() => {
    locations.call(() => bime.locations.list(token))
    products.call(() => bime.products.list(token))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (products.state.status !== 'success') return
    let cancelled = false
    Promise.all(products.state.data.map(p => bime.products.get(p.id, token)))
      .then(full => {
        if (cancelled) return
        const byProduct: Record<string, ProductVariantResponse[]> = {}
        const lookup: VariantLookup = {}
        full.forEach(p => {
          const variants = p.variants ?? []
          byProduct[p.id] = variants
          variants.forEach(v => {
            lookup[v.id] = { sku: v.sku, productName: p.name, optionsLabel: v.options.map(o => o.value).join(', ') }
          })
        })
        setVariantsByProduct(byProduct)
        setVariantLookup(lookup)
      })
      .catch(() => {})
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [products.state.status])

  const locationLookup: LocationLookup = {}
  if (locations.state.status === 'success') {
    locations.state.data.forEach(l => { locationLookup[l.id] = l })
  }
  const locationOptions = locations.state.status === 'success' ? locations.state.data : []
  const productOptions = products.state.status === 'success' ? products.state.data : []

  // Record Movement
  const record = useApiCall<StockMovementResponse>()
  const [recordProductId, setRecordProductId] = useState('')
  const [variantId, setVariantId] = useState('')
  const [locationId, setLocationId] = useState('')
  const [movementType, setMovementType] = useState<MovementType>('INBOUND')
  const [delta, setDelta] = useState(0)
  const [note, setNote] = useState('')

  // List Movements
  const movements = useApiCall<StockMovementResponse[]>()
  const [movFilterProduct, setMovFilterProduct] = useState('')
  const [movFilterVariant, setMovFilterVariant] = useState('')
  const [movFilterLocation, setMovFilterLocation] = useState('')

  // List Balances
  const balances = useApiCall<StockBalanceResponse[]>()
  const [balFilterProduct, setBalFilterProduct] = useState('')
  const [balFilterVariant, setBalFilterVariant] = useState('')
  const [balFilterLocation, setBalFilterLocation] = useState('')

  return (
    <div className="page">

      {/* ── Record Movement ── */}
      {permissions.canManageBime && (
        <div className="panel">
          <h2>Record Movement</h2>
          <p className="panel-hint">Movements are immutable — corrections must be recorded as ADJUSTMENT.</p>
          <div className="fields">
            <div className="field">
              <label>Product</label>
              <select
                value={recordProductId}
                onChange={e => { setRecordProductId(e.target.value); setVariantId('') }}
              >
                <option value="">Select a product…</option>
                {productOptions.map(p => <option key={p.id} value={p.id}>{p.name} ({p.sku})</option>)}
              </select>
            </div>
            <div className="field">
              <label>Variant</label>
              <select value={variantId} onChange={e => setVariantId(e.target.value)} disabled={!recordProductId}>
                <option value="">Select a variant…</option>
                {(variantsByProduct[recordProductId] ?? []).map(v => (
                  <option key={v.id} value={v.id}>{variantLabel(v)}</option>
                ))}
              </select>
            </div>
            <div className="field">
              <label>Location</label>
              <select value={locationId} onChange={e => setLocationId(e.target.value)}>
                <option value="">Select a location…</option>
                {locationOptions.map(l => <option key={l.id} value={l.id}>{l.name} ({l.code})</option>)}
              </select>
            </div>
            <div className="field">
              <label>Movement Type</label>
              <select value={movementType} onChange={e => setMovementType(e.target.value as MovementType)}>
                {MOVEMENT_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
            <div className="field">
              <label>Delta</label>
              <input type="number" value={delta} onChange={e => setDelta(parseInt(e.target.value, 10) || 0)} />
            </div>
            <div className="field">
              <label>Note</label>
              <input value={note} onChange={e => setNote(e.target.value)} placeholder="Received from supplier PO-2026-042" />
            </div>
          </div>
          <div className="actions">
            <button
              className="btn btn-primary"
              disabled={record.state.status === 'loading' || !variantId || !locationId || delta === 0}
              onClick={() => record.call(() => bime.stock.recordMovement(
                { variantId, locationId, movementType, delta, note: note.trim() || undefined },
                token,
              ))}
            >
              {record.state.status === 'loading' ? 'Recording…' : 'Record'}
            </button>
          </div>
          <Feedback state={record.state} successLabel="Movement recorded." />
        </div>
      )}

      {/* ── List Movements ── */}
      <div className="panel">
        <h2>Movements</h2>
        <div className="fields">
          <div className="field">
            <label>Product (optional)</label>
            <select value={movFilterProduct} onChange={e => { setMovFilterProduct(e.target.value); setMovFilterVariant('') }}>
              <option value="">All products</option>
              {productOptions.map(p => <option key={p.id} value={p.id}>{p.name} ({p.sku})</option>)}
            </select>
          </div>
          <div className="field">
            <label>Variant (optional)</label>
            <select value={movFilterVariant} onChange={e => setMovFilterVariant(e.target.value)} disabled={!movFilterProduct}>
              <option value="">All variants</option>
              {(variantsByProduct[movFilterProduct] ?? []).map(v => (
                <option key={v.id} value={v.id}>{variantLabel(v)}</option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>Location (optional)</label>
            <select value={movFilterLocation} onChange={e => setMovFilterLocation(e.target.value)}>
              <option value="">All locations</option>
              {locationOptions.map(l => <option key={l.id} value={l.id}>{l.name} ({l.code})</option>)}
            </select>
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-outline"
            disabled={movements.state.status === 'loading'}
            onClick={() => movements.call(() => bime.stock.listMovements(token, {
              variantId: movFilterVariant || undefined,
              locationId: movFilterLocation || undefined,
            }))}
          >
            {movements.state.status === 'loading' ? 'Loading…' : 'Load'}
          </button>
        </div>
        {movements.state.status === 'success' && (
          <MovementsTable movements={movements.state.data} variantLookup={variantLookup} locationLookup={locationLookup} />
        )}
        {movements.state.status === 'error' && <div className="error">{movements.state.message}</div>}
      </div>

      {/* ── List Balances ── */}
      <div className="panel">
        <h2>Balances</h2>
        <div className="fields">
          <div className="field">
            <label>Product (optional)</label>
            <select value={balFilterProduct} onChange={e => { setBalFilterProduct(e.target.value); setBalFilterVariant('') }}>
              <option value="">All products</option>
              {productOptions.map(p => <option key={p.id} value={p.id}>{p.name} ({p.sku})</option>)}
            </select>
          </div>
          <div className="field">
            <label>Variant (optional)</label>
            <select value={balFilterVariant} onChange={e => setBalFilterVariant(e.target.value)} disabled={!balFilterProduct}>
              <option value="">All variants</option>
              {(variantsByProduct[balFilterProduct] ?? []).map(v => (
                <option key={v.id} value={v.id}>{variantLabel(v)}</option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>Location (optional)</label>
            <select value={balFilterLocation} onChange={e => setBalFilterLocation(e.target.value)}>
              <option value="">All locations</option>
              {locationOptions.map(l => <option key={l.id} value={l.id}>{l.name} ({l.code})</option>)}
            </select>
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-outline"
            disabled={balances.state.status === 'loading'}
            onClick={() => balances.call(() => bime.stock.listBalances(token, {
              variantId: balFilterVariant || undefined,
              locationId: balFilterLocation || undefined,
            }))}
          >
            {balances.state.status === 'loading' ? 'Loading…' : 'Load'}
          </button>
        </div>
        {balances.state.status === 'success' && (
          <BalancesTable balances={balances.state.data} variantLookup={variantLookup} locationLookup={locationLookup} />
        )}
        {balances.state.status === 'error' && <div className="error">{balances.state.message}</div>}
      </div>

    </div>
  )
}
