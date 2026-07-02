import { useEffect, useState, type ChangeEvent } from 'react'
import { bime } from '../api/bime'
import { useApiCall } from '../hooks/useApiCall'
import { Feedback } from '../components/Feedback'
import { CopyButton } from '../components/CopyButton'
import type { Permissions } from '../auth'
import type {
  LocationResponse,
  ProductMetadataAssignmentItem,
  ProductMetadataResponse,
  ProductRequest,
  ProductResponse,
  ProductVariantResponse,
} from '../types'

type LocationLookup = Record<string, LocationResponse>

interface Props {
  token: string
  permissions: Permissions
}

interface AssignmentRow {
  metadataId: string
  optionIds: string[]
}

function splitIds(value: string): string[] {
  return value.split(',').map(s => s.trim()).filter(Boolean)
}

function buildAssignments(rows: AssignmentRow[]): ProductMetadataAssignmentItem[] {
  return rows
    .filter(r => r.metadataId)
    .map(r => ({ metadataId: r.metadataId, optionIds: r.optionIds }))
}

function selectedValues(e: ChangeEvent<HTMLSelectElement>): string[] {
  return Array.from(e.target.selectedOptions).map(o => o.value)
}

function AssignmentsInput({ value, onChange, metadataDefs }: {
  value: AssignmentRow[]
  onChange: (rows: AssignmentRow[]) => void
  metadataDefs: ProductMetadataResponse[]
}) {
  return (
    <div className="roles-input">
      {value.map((row, i) => {
        const options = metadataDefs.find(m => m.id === row.metadataId)?.options ?? []
        return (
          <div key={i} className="role-row">
            <select
              value={row.metadataId}
              onChange={e => onChange(value.map((r, j) => j === i ? { metadataId: e.target.value, optionIds: [] } : r))}
              className="role-row-svc"
            >
              <option value="">Select metadata…</option>
              {metadataDefs.map(m => <option key={m.id} value={m.id}>{m.name}</option>)}
            </select>
            <select
              multiple
              value={row.optionIds}
              onChange={e => onChange(value.map((r, j) => j === i ? { ...r, optionIds: selectedValues(e) } : r))}
              className="role-row-name"
              disabled={!row.metadataId}
              size={Math.min(4, Math.max(2, options.length))}
            >
              {options.map(o => <option key={o.id} value={o.id}>{o.value}</option>)}
            </select>
            <button className="btn btn-outline btn-sm" type="button" onClick={() => onChange(value.filter((_, j) => j !== i))}>−</button>
          </div>
        )
      })}
      <button className="btn btn-outline btn-sm" type="button" onClick={() => onChange([...value, { metadataId: '', optionIds: [] }])}>
        + Add metadata assignment
      </button>
    </div>
  )
}

function ProductsTable({ products }: { products: ProductResponse[] }) {
  if (products.length === 0) {
    return <div className="empty-state">No products registered yet.</div>
  }
  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>SKU</th>
          <th>Name</th>
          <th>Active</th>
          <th>Variants</th>
          <th>ID</th>
        </tr>
      </thead>
      <tbody>
        {products.map(p => (
          <tr key={p.id}>
            <td>{p.sku}</td>
            <td>{p.name}</td>
            <td>
              <span className={`status-badge ${p.isActive ? 'status-ok' : 'status-fail'}`}>
                {p.isActive ? 'Active' : 'Inactive'}
              </span>
            </td>
            <td className="td-muted">{p.variants?.length ?? '—'}</td>
            <td>
              <div className="td-id">
                <span>{p.id}</span>
                <CopyButton text={p.id} />
              </div>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function VariantsTable({ variants, locationLookup }: { variants: ProductVariantResponse[]; locationLookup: LocationLookup }) {
  if (variants.length === 0) {
    return <div className="empty-state">No variants yet.</div>
  }
  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>SKU</th>
          <th>Options</th>
          <th>Active</th>
          <th>Stock</th>
          <th>ID</th>
        </tr>
      </thead>
      <tbody>
        {variants.map(v => (
          <tr key={v.id}>
            <td className="td-muted">{v.sku ?? '—'}</td>
            <td>
              {v.options.map(o => (
                <span key={o.id} className="role-badge" title={o.id} style={{ marginRight: '6px' }}>{o.value}</span>
              ))}
            </td>
            <td>
              <span className={`status-badge ${v.isActive ? 'status-ok' : 'status-fail'}`}>
                {v.isActive ? 'Active' : 'Inactive'}
              </span>
            </td>
            <td className="td-muted">
              {v.stock.length === 0 ? '—' : v.stock.map(s => {
                const loc = locationLookup[s.locationId]
                return `${s.quantity} @ ${loc ? `${loc.name} (${loc.code})` : s.locationId.slice(0, 8) + '…'}`
              }).join(', ')}
            </td>
            <td>
              <div className="td-id">
                <span>{v.id}</span>
                <CopyButton text={v.id} />
              </div>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

const EMPTY_PRODUCT_FORM: ProductRequest = { sku: '', name: '', description: '' }

export default function BimeProductsPage({ token, permissions }: Props) {
  // Locations, loaded once to translate location IDs into names in stock summaries.
  const locations = useApiCall<LocationResponse[]>()
  useEffect(() => {
    locations.call(() => bime.locations.list(token))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const locationLookup: LocationLookup = {}
  if (locations.state.status === 'success') {
    locations.state.data.forEach(l => { locationLookup[l.id] = l })
  }

  // Metadata definitions, loaded once to power the Assign Metadata dropdowns.
  const metadataDefs = useApiCall<ProductMetadataResponse[]>()
  useEffect(() => {
    metadataDefs.call(() => bime.metadata.list(token))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const metadataDefsList = metadataDefs.state.status === 'success' ? metadataDefs.state.data : []

  // All Products
  const list = useApiCall<ProductResponse[]>()

  // Create Product
  const create = useApiCall<ProductResponse>()
  const [createForm, setCreateForm] = useState<ProductRequest>(EMPTY_PRODUCT_FORM)

  // Look Up Product
  const lookup = useApiCall<ProductResponse>()
  const [lookupId, setLookupId] = useState('')

  // Update Product
  const update = useApiCall<ProductResponse>()
  const [updateId, setUpdateId] = useState('')
  const [updateForm, setUpdateForm] = useState<ProductRequest>(EMPTY_PRODUCT_FORM)

  // Deactivate Product
  const deactivate = useApiCall<void>()
  const [deactivateId, setDeactivateId] = useState('')

  // Assign Metadata
  const assignMetadata = useApiCall<void>()
  const [assignProductId, setAssignProductId] = useState('')
  const [assignRows, setAssignRows] = useState<AssignmentRow[]>([])

  // Create Variant
  const createVariant = useApiCall<ProductVariantResponse>()
  const [variantProductId, setVariantProductId] = useState('')
  const [variantOptionIds, setVariantOptionIds] = useState('')
  const [variantSku, setVariantSku] = useState('')

  // List Variants for a Product
  const listVariants = useApiCall<ProductVariantResponse[]>()
  const [listVariantsProductId, setListVariantsProductId] = useState('')

  // Deactivate Variant
  const deactivateVariant = useApiCall<void>()
  const [deactivateVariantProductId, setDeactivateVariantProductId] = useState('')
  const [deactivateVariantId, setDeactivateVariantId] = useState('')

  function loadIntoUpdateForm(product: ProductResponse) {
    setUpdateId(product.id)
    setUpdateForm({ sku: product.sku, name: product.name, description: product.description ?? '' })
  }

  return (
    <div className="page">

      {/* ── All Products ── */}
      <div className="panel">
        <h2>All Products</h2>
        <div className="actions">
          <button
            className="btn btn-outline"
            disabled={list.state.status === 'loading'}
            onClick={() => list.call(() => bime.products.list(token))}
          >
            {list.state.status === 'loading' ? 'Loading…' : 'Load'}
          </button>
        </div>
        {list.state.status === 'success' && <ProductsTable products={list.state.data} />}
        {list.state.status === 'error' && <div className="error">{list.state.message}</div>}
      </div>

      {/* ── Create Product ── */}
      {permissions.canManageBime && (
        <div className="panel">
          <h2>Create Product</h2>
          <div className="fields">
            <div className="field">
              <label>SKU</label>
              <input value={createForm.sku} onChange={e => setCreateForm(f => ({ ...f, sku: e.target.value }))} placeholder="WIDGET-001" />
            </div>
            <div className="field">
              <label>Name</label>
              <input value={createForm.name} onChange={e => setCreateForm(f => ({ ...f, name: e.target.value }))} placeholder="Widget" />
            </div>
            <div className="field">
              <label>Description</label>
              <input value={createForm.description} onChange={e => setCreateForm(f => ({ ...f, description: e.target.value }))} />
            </div>
          </div>
          <div className="actions">
            <button
              className="btn btn-primary"
              disabled={create.state.status === 'loading' || !createForm.sku.trim() || !createForm.name.trim()}
              onClick={() => create.call(() => bime.products.create(createForm, token))}
            >
              {create.state.status === 'loading' ? 'Creating…' : 'Create'}
            </button>
          </div>
          {create.state.status === 'success' && <div className="success">Product created: {create.state.data.id}</div>}
          {create.state.status === 'error' && <div className="error">{create.state.message}</div>}
        </div>
      )}

      {/* ── Look Up Product ── */}
      <div className="panel">
        <h2>Look Up Product</h2>
        <div className="fields">
          <div className="field">
            <label>Product ID</label>
            <input value={lookupId} onChange={e => setLookupId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" spellCheck={false} />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-outline"
            disabled={lookup.state.status === 'loading' || !lookupId.trim()}
            onClick={() => lookup.call(() => bime.products.get(lookupId.trim(), token))}
          >
            {lookup.state.status === 'loading' ? 'Fetching…' : 'Fetch'}
          </button>
        </div>
        {lookup.state.status === 'success' && (() => {
          const product = lookup.state.data
          return (
            <>
              <div className="entity-card">
                <div className="entity-name">{product.name}</div>
                <div className="entity-detail">{product.sku}{product.description ? ` — ${product.description}` : ''}</div>
                <div className="entity-id-row">
                  <span className="entity-id-label">ID</span>
                  <span className="entity-id-value">{product.id}</span>
                  <CopyButton text={product.id} />
                </div>
              </div>
              {product.metadata && product.metadata.length > 0 && (
                <div style={{ marginTop: '12px' }}>
                  <h3 style={{ fontSize: '13px', color: '#6c757d', marginBottom: '6px' }}>Metadata</h3>
                  {product.metadata.map(m => (
                    <div key={m.metadataId} style={{ marginBottom: '4px' }}>
                      <strong>{m.metadataName}:</strong>{' '}
                      {m.selectedOptions.map(o => (
                        <span key={o.id} className="role-badge" style={{ marginRight: '6px' }}>{o.value}</span>
                      ))}
                    </div>
                  ))}
                </div>
              )}
              <div style={{ marginTop: '12px' }}>
                <h3 style={{ fontSize: '13px', color: '#6c757d', marginBottom: '6px' }}>Variants</h3>
                <VariantsTable variants={product.variants ?? []} locationLookup={locationLookup} />
              </div>
              {permissions.canManageBime && (
                <div style={{ marginTop: '8px' }}>
                  <button className="btn btn-outline btn-sm" onClick={() => loadIntoUpdateForm(product)}>
                    Load into update form ↓
                  </button>
                </div>
              )}
            </>
          )
        })()}
        {lookup.state.status === 'error' && <div className="error">{lookup.state.message}</div>}
      </div>

      {/* ── Update Product ── */}
      {permissions.canManageBime && (
        <div className="panel">
          <h2>Update Product</h2>
          <div className="fields">
            <div className="field">
              <label>Product ID</label>
              <input value={updateId} onChange={e => setUpdateId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" spellCheck={false} />
            </div>
            <div className="field">
              <label>SKU</label>
              <input value={updateForm.sku} onChange={e => setUpdateForm(f => ({ ...f, sku: e.target.value }))} />
            </div>
            <div className="field">
              <label>Name</label>
              <input value={updateForm.name} onChange={e => setUpdateForm(f => ({ ...f, name: e.target.value }))} />
            </div>
            <div className="field">
              <label>Description</label>
              <input value={updateForm.description} onChange={e => setUpdateForm(f => ({ ...f, description: e.target.value }))} />
            </div>
          </div>
          <div className="actions">
            <button
              className="btn btn-primary"
              disabled={update.state.status === 'loading' || !updateId.trim() || !updateForm.sku.trim() || !updateForm.name.trim()}
              onClick={() => update.call(() => bime.products.update(updateId.trim(), updateForm, token))}
            >
              {update.state.status === 'loading' ? 'Updating…' : 'Update'}
            </button>
          </div>
          <Feedback state={update.state} successLabel="Product updated." />
        </div>
      )}

      {/* ── Deactivate Product ── */}
      {permissions.canManageBime && (
        <div className="panel">
          <h2>Deactivate Product</h2>
          <p className="panel-hint">Soft-deletes the product. Variants and stock records are preserved.</p>
          <div className="fields">
            <div className="field">
              <label>Product ID</label>
              <input value={deactivateId} onChange={e => setDeactivateId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" spellCheck={false} />
            </div>
          </div>
          <div className="actions">
            <button
              className="btn btn-danger"
              disabled={deactivate.state.status === 'loading' || !deactivateId.trim()}
              onClick={() => {
                if (!window.confirm(`Deactivate product ${deactivateId.trim()}?`)) return
                deactivate.call(() => bime.products.deactivate(deactivateId.trim(), token))
              }}
            >
              {deactivate.state.status === 'loading' ? 'Deactivating…' : 'Deactivate'}
            </button>
          </div>
          <Feedback state={deactivate.state} successLabel="Product deactivated." />
        </div>
      )}

      {/* ── Assign Metadata ── */}
      {permissions.canManageBime && (
        <div className="panel">
          <h2>Assign Metadata</h2>
          <p className="panel-hint">Replaces the full set of metadata assignments for the product.</p>
          <div className="fields">
            <div className="field">
              <label>Product ID</label>
              <input value={assignProductId} onChange={e => setAssignProductId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" spellCheck={false} />
            </div>
          </div>
          <div className="field" style={{ marginBottom: '14px' }}>
            <label style={{ marginBottom: '8px' }}>Assignments</label>
            <AssignmentsInput value={assignRows} onChange={setAssignRows} metadataDefs={metadataDefsList} />
          </div>
          <div className="actions">
            <button
              className="btn btn-primary"
              disabled={assignMetadata.state.status === 'loading' || !assignProductId.trim()}
              onClick={() => assignMetadata.call(() => bime.products.assignMetadata(
                assignProductId.trim(), buildAssignments(assignRows), token,
              ))}
            >
              {assignMetadata.state.status === 'loading' ? 'Assigning…' : 'Assign'}
            </button>
          </div>
          <Feedback state={assignMetadata.state} successLabel="Metadata assignments updated." />
        </div>
      )}

      {/* ── Create Variant ── */}
      {permissions.canManageBime && (
        <div className="panel">
          <h2>Create Variant</h2>
          <div className="fields">
            <div className="field">
              <label>Product ID</label>
              <input value={variantProductId} onChange={e => setVariantProductId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" spellCheck={false} />
            </div>
            <div className="field">
              <label>Option IDs (comma-separated)</label>
              <input value={variantOptionIds} onChange={e => setVariantOptionIds(e.target.value)} placeholder="red-id, xl-id" spellCheck={false} />
            </div>
            <div className="field">
              <label>SKU (optional)</label>
              <input value={variantSku} onChange={e => setVariantSku(e.target.value)} placeholder="WIDGET-001-RED-XL" />
            </div>
          </div>
          <div className="actions">
            <button
              className="btn btn-primary"
              disabled={createVariant.state.status === 'loading' || !variantProductId.trim()}
              onClick={() => createVariant.call(() => bime.variants.create(
                variantProductId.trim(),
                { optionIds: splitIds(variantOptionIds), sku: variantSku.trim() || undefined },
                token,
              ))}
            >
              {createVariant.state.status === 'loading' ? 'Creating…' : 'Create'}
            </button>
          </div>
          {createVariant.state.status === 'success' && <div className="success">Variant created: {createVariant.state.data.id}</div>}
          {createVariant.state.status === 'error' && <div className="error">{createVariant.state.message}</div>}
        </div>
      )}

      {/* ── List Variants for a Product ── */}
      <div className="panel">
        <h2>List Variants</h2>
        <div className="fields">
          <div className="field">
            <label>Product ID</label>
            <input value={listVariantsProductId} onChange={e => setListVariantsProductId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" spellCheck={false} />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-outline"
            disabled={listVariants.state.status === 'loading' || !listVariantsProductId.trim()}
            onClick={() => listVariants.call(() => bime.variants.list(listVariantsProductId.trim(), token))}
          >
            {listVariants.state.status === 'loading' ? 'Loading…' : 'Load'}
          </button>
        </div>
        {listVariants.state.status === 'success' && <VariantsTable variants={listVariants.state.data} locationLookup={locationLookup} />}
        {listVariants.state.status === 'error' && <div className="error">{listVariants.state.message}</div>}
      </div>

      {/* ── Deactivate Variant ── */}
      {permissions.canManageBime && (
        <div className="panel">
          <h2>Deactivate Variant</h2>
          <div className="fields">
            <div className="field">
              <label>Product ID</label>
              <input value={deactivateVariantProductId} onChange={e => setDeactivateVariantProductId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" spellCheck={false} />
            </div>
            <div className="field">
              <label>Variant ID</label>
              <input value={deactivateVariantId} onChange={e => setDeactivateVariantId(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" spellCheck={false} />
            </div>
          </div>
          <div className="actions">
            <button
              className="btn btn-danger"
              disabled={deactivateVariant.state.status === 'loading' || !deactivateVariantProductId.trim() || !deactivateVariantId.trim()}
              onClick={() => {
                if (!window.confirm(`Deactivate variant ${deactivateVariantId.trim()}?`)) return
                deactivateVariant.call(() => bime.variants.deactivate(
                  deactivateVariantProductId.trim(), deactivateVariantId.trim(), token,
                ))
              }}
            >
              {deactivateVariant.state.status === 'loading' ? 'Deactivating…' : 'Deactivate'}
            </button>
          </div>
          <Feedback state={deactivateVariant.state} successLabel="Variant deactivated." />
        </div>
      )}

    </div>
  )
}
