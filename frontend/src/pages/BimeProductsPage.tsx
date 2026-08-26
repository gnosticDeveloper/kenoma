import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { bime } from '../api/bime'
import { formatMoney } from '../lib/money'
import { useApiCall } from '../hooks/useApiCall'
import { useToast } from '../components/Toast'
import { Modal } from '../components/Modal'
import { Tabs } from '../components/Tabs'
import { DataTable, type Column } from '../components/DataTable'
import { RowActionsMenu } from '../components/RowActionsMenu'
import { Combobox, MultiCombobox } from '../components/Combobox'
import { CopyButton } from '../components/CopyButton'
import { Feedback } from '../components/Feedback'
import { FilterChips, FilterDisclosure, toggleOptionId } from '../components/OptionFilter'
import type { Permissions } from '../auth'
import type {
  LocationResponse,
  ProductMetadataAssignmentItem,
  ProductMetadataResponse,
  ProductRequest,
  ProductResponse,
  ProductVariantRequest,
  ProductVariantResponse,
  VariantPriceUpdate,
} from '../types'

interface Props {
  token: string
  permissions: Permissions
}

interface AssignmentRow {
  key: string
  metadataId: string
  optionIds: string[]
}

function newAssignmentRowKey(): string {
  return crypto.randomUUID()
}

const VIEW_CURRENCY_KEY = 'kenoma.bime.viewCurrency'

function buildAssignments(rows: AssignmentRow[]): ProductMetadataAssignmentItem[] {
  return rows.filter(r => r.metadataId).map(r => ({ metadataId: r.metadataId, optionIds: r.optionIds }))
}

function AssignmentsInput({ value, onChange, metadataDefs, addLabel }: {
  value: AssignmentRow[]
  onChange: (rows: AssignmentRow[]) => void
  metadataDefs: ProductMetadataResponse[]
  addLabel?: string
}) {
  const { t } = useTranslation()
  const metadataItems = metadataDefs.map(m => ({ id: m.id, label: m.name }))
  return (
    <div className="roles-input">
      {value.map(row => {
        const options = metadataDefs.find(m => m.id === row.metadataId)?.options ?? []
        const optionItems = options.map(o => ({ id: o.id, label: o.value }))
        return (
          <div key={row.key} className="role-row">
            <Combobox
              items={metadataItems}
              value={row.metadataId || null}
              onChange={id => onChange(value.map(r => r.key === row.key ? { key: row.key, metadataId: id ?? '', optionIds: [] } : r))}
              placeholder={t('bimeProductsPage.metadataPlaceholder')}
            />
            <MultiCombobox
              items={optionItems}
              value={row.optionIds}
              onChange={ids => onChange(value.map(r => r.key === row.key ? { ...r, optionIds: ids } : r))}
              placeholder={t('bimeProductsPage.optionsPlaceholder')}
              disabled={!row.metadataId}
            />
            <button className="btn btn-outline btn-sm" type="button" onClick={() => onChange(value.filter(r => r.key !== row.key))}>−</button>
          </div>
        )
      })}
      <button className="btn btn-outline btn-sm" type="button" onClick={() => onChange([...value, { key: newAssignmentRowKey(), metadataId: '', optionIds: [] }])}>
        {addLabel ?? t('bimeProductsPage.addAssignment')}
      </button>
    </div>
  )
}

const EMPTY_PRODUCT_FORM: ProductRequest = { sku: '', name: '', description: '' }

export default function BimeProductsPage({ token, permissions }: Props) {
  const { t, i18n } = useTranslation()
  const toast = useToast()
  const [activeTab, setActiveTab] = useState('products')

  const locations = useApiCall<LocationResponse[]>()
  useEffect(() => { locations.call(() => bime.locations.list(token)) /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [])
  const locationLookup: Record<string, LocationResponse> = {}
  if (locations.state.status === 'success') locations.state.data.forEach(l => { locationLookup[l.id] = l })

  const metadataDefs = useApiCall<ProductMetadataResponse[]>()
  useEffect(() => { metadataDefs.call(() => bime.metadata.list(token)) /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [])
  const metadataDefsList = metadataDefs.state.status === 'success' ? metadataDefs.state.data : []

  const allProducts = useApiCall<ProductResponse[]>()
  const allProductsList = allProducts.state.status === 'success' ? allProducts.state.data : []

  const [productOptionFilter, setProductOptionFilter] = useState<string[]>([])
  const [productMatchAll, setProductMatchAll] = useState(false)
  function toggleProductOptionFilter(optionId: string) {
    setProductOptionFilter(prev => toggleOptionId(prev, optionId))
  }
  const list = useApiCall<ProductResponse[]>()
  function reload() {
    list.call(() => bime.products.list(token, productOptionFilter.length ? productOptionFilter : undefined, productMatchAll))
    allProducts.call(() => bime.products.list(token))
  }
  useEffect(reload, [token, productOptionFilter, productMatchAll])
  const products = list.state.status === 'success' ? list.state.data : []

  // ── Create / Edit product ──
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<ProductResponse | null>(null)
  const [form, setForm] = useState<ProductRequest>(EMPTY_PRODUCT_FORM)
  const save = useApiCall<ProductResponse>()
  const deactivate = useApiCall<void>()

  useEffect(() => {
    if (save.state.status !== 'success') return
    setModalOpen(false)
    reload()
    toast.show(t(editing ? 'bimeProductsPage.updated' : 'bimeProductsPage.created'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [save.state])

  function openCreate() {
    setEditing(null)
    setForm(EMPTY_PRODUCT_FORM)
    setModalOpen(true)
  }

  function openVariants(product: ProductResponse) {
    setSelectedProductId(product.id)
    setActiveTab('variants')
  }

  function openEdit(product: ProductResponse) {
    setEditing(product)
    setForm({ sku: product.sku, name: product.name, description: product.description ?? '' })
    setModalOpen(true)
  }

  function submit() {
    save.call(() => editing ? bime.products.update(editing.id, form, token) : bime.products.create(form, token))
  }

  function remove(product: ProductResponse) {
    if (!window.confirm(t('bimeProductsPage.deactivateConfirm', { name: product.name }))) return
    deactivate.call(() => bime.products.deactivate(product.id, token)).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
      reload()
      toast.show(t('bimeProductsPage.deactivated'))
    })
  }

  // ── Assign metadata ──
  const [assignTarget, setAssignTarget] = useState<ProductResponse | null>(null)
  const [assignRows, setAssignRows] = useState<AssignmentRow[]>([])
  const assignMetadata = useApiCall<void>()

  useEffect(() => {
    if (assignMetadata.state.status !== 'success') return
    setAssignTarget(null)
    reload()
    toast.show(t('bimeProductsPage.assignmentsSaved'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [assignMetadata.state])

  function openAssign(product: ProductResponse) {
    setAssignTarget(product)
    setAssignRows((product.metadata ?? []).map(m => ({
      key: newAssignmentRowKey(),
      metadataId: m.metadataId,
      optionIds: m.selectedOptions.map(o => o.id),
    })))
  }

  // ── Variants tab ──
  const [selectedProductId, setSelectedProductId] = useState<string | null>(null)
  const [viewCurrency, setViewCurrency] = useState(() => localStorage.getItem(VIEW_CURRENCY_KEY) ?? '')
  useEffect(() => { localStorage.setItem(VIEW_CURRENCY_KEY, viewCurrency) }, [viewCurrency])
  // ISO 4217 codes are always 3 letters - only apply (and refetch) once the field is empty
  // (native prices) or a full code, not on every keystroke while typing one. Decoupled from
  // viewCurrency so refreshes triggered by other actions (create/edit/reprice) still use the
  // last valid currency instead of being blocked by an in-progress partial edit.
  const [appliedViewCurrency, setAppliedViewCurrency] = useState(viewCurrency)
  useEffect(() => {
    if (viewCurrency.length === 0 || viewCurrency.length === 3) setAppliedViewCurrency(viewCurrency)
  }, [viewCurrency])
  const [variantOptionFilter, setVariantOptionFilter] = useState<string[]>([])
  const [variantMatchAll, setVariantMatchAll] = useState(false)
  function toggleVariantOptionFilter(optionId: string) {
    setVariantOptionFilter(prev => toggleOptionId(prev, optionId))
  }
  const variants = useApiCall<ProductVariantResponse[]>()
  function reloadVariants() {
    if (selectedProductId) {
      variants.call(() => bime.variants.list(
        selectedProductId, token, appliedViewCurrency || undefined,
        variantOptionFilter.length ? variantOptionFilter : undefined, variantMatchAll,
      ))
    } else if (variantOptionFilter.length > 0) {
      variants.call(() => bime.variants.search(variantOptionFilter, token, appliedViewCurrency || undefined, variantMatchAll))
    }
  }
  useEffect(reloadVariants, [selectedProductId, appliedViewCurrency, variantOptionFilter, variantMatchAll])
  const variantList = variants.state.status === 'success' ? variants.state.data : []
  const searchingAcrossProducts = !selectedProductId && variantOptionFilter.length > 0

  const [variantModalOpen, setVariantModalOpen] = useState(false)
  const [variantPrice, setVariantPrice] = useState('')
  const [variantPriceCurrency, setVariantPriceCurrency] = useState('')
  const [variantRows, setVariantRows] = useState<AssignmentRow[]>([])
  const createVariant = useApiCall<ProductVariantResponse>()
  const deactivateVariant = useApiCall<void>()

  useEffect(() => {
    if (createVariant.state.status !== 'success') return
    setVariantModalOpen(false)
    setVariantPrice('')
    setVariantPriceCurrency('')
    setVariantRows([])
    reloadVariants()
    toast.show(t('bimeProductsPage.variantCreated'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [createVariant.state])

  function submitVariant() {
    if (!selectedProductId) return
    const optionIds = buildAssignments(variantRows).flatMap(a => a.optionIds)
    const dto: ProductVariantRequest = {
      optionIds,
      price: variantPrice.trim() ? Number(variantPrice) : undefined,
      priceCurrency: variantPrice.trim() ? (variantPriceCurrency.trim() || undefined) : undefined,
    }
    createVariant.call(() => bime.variants.create(selectedProductId, dto, token))
  }

  function removeVariant(v: ProductVariantResponse) {
    if (!window.confirm(t('bimeProductsPage.deactivateVariantConfirm'))) return
    deactivateVariant.call(() => bime.variants.deactivate(v.productId, v.id, token)).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
      reloadVariants()
      toast.show(t('bimeProductsPage.variantDeactivated'))
    })
  }

  // ── Edit variant (price) ──
  const [editingVariant, setEditingVariant] = useState<ProductVariantResponse | null>(null)
  const [editVariantPrice, setEditVariantPrice] = useState('')
  const [editVariantPriceCurrency, setEditVariantPriceCurrency] = useState('')
  const updateVariant = useApiCall<ProductVariantResponse>()

  useEffect(() => {
    if (updateVariant.state.status !== 'success') return
    setEditingVariant(null)
    reloadVariants()
    toast.show(t('bimeProductsPage.variantUpdated'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [updateVariant.state])

  function openEditVariant(v: ProductVariantResponse) {
    setEditingVariant(v)
    setEditVariantPrice(v.price != null ? String(v.price) : '')
    setEditVariantPriceCurrency(v.priceCurrency ?? '')
  }

  function submitEditVariant() {
    if (!editingVariant) return
    // optionIds isn't read by PATCH on the backend - only create uses it - so it's omitted here.
    const dto: ProductVariantRequest = {
      optionIds: [],
      price: editVariantPrice.trim() ? Number(editVariantPrice) : undefined,
      priceCurrency: editVariantPrice.trim() ? (editVariantPriceCurrency.trim() || undefined) : undefined,
    }
    updateVariant.call(() => bime.variants.patch(editingVariant.productId, editingVariant.id, dto, token))
  }

  // ── Batch reprice selected variants ──
  const [selectedVariantIds, setSelectedVariantIds] = useState<Set<string>>(new Set())
  function toggleVariantSelected(id: string) {
    setSelectedVariantIds(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      return next
    })
  }
  const [repriceModalOpen, setRepriceModalOpen] = useState(false)
  const [repriceValue, setRepriceValue] = useState('')
  const batchReprice = useApiCall<string[]>()

  useEffect(() => {
    if (batchReprice.state.status !== 'success') return
    setRepriceModalOpen(false)
    setRepriceValue('')
    setSelectedVariantIds(new Set())
    reloadVariants()
    toast.show(t('bimeProductsPage.pricesUpdated'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [batchReprice.state])

  function submitBatchReprice() {
    const price = Number(repriceValue)
    const items: VariantPriceUpdate[] = Array.from(selectedVariantIds).map(variantId => ({ variantId, price }))
    batchReprice.call(() => bime.variants.batchUpdatePrices({ items }, token))
  }

  const productColumns: Column<ProductResponse>[] = [
    { key: 'sku', header: t('bimeProductsPage.sku'), render: p => <span className="td-muted">{p.sku}</span> },
    { key: 'name', header: t('bimeProductsPage.name'), render: p => p.name, sortValue: p => p.name },
    {
      key: 'active',
      header: t('bimeProductsPage.active'),
      render: p => (
        <span className={`status-badge ${p.isActive ? 'status-ok' : 'status-fail'}`}>
          {p.isActive ? t('bimeProductsPage.active') : t('bimeProductsPage.inactive')}
        </span>
      ),
    },
    { key: 'variants', header: t('bimeProductsPage.variants'), render: p => <span className="td-muted">{p.variantCount ?? '—'}</span> },
    ...(permissions.canManageBime ? [{
      key: 'actions',
      header: '',
      render: (p: ProductResponse) => (
        <RowActionsMenu actions={[
          { label: t('common.actions.edit'), onClick: () => openEdit(p) },
          { label: t('bimeProductsPage.assignMetadata'), onClick: () => openAssign(p) },
          { label: t('bimeProductsPage.manageVariants'), onClick: () => openVariants(p) },
          { label: t('common.actions.deactivate'), onClick: () => remove(p), danger: true },
        ]} />
      ),
    }] : []),
  ]

  const variantColumns: Column<ProductVariantResponse>[] = [
    ...(permissions.canManageBime ? [{
      key: 'select',
      header: '',
      render: (v: ProductVariantResponse) => (
        <input
          type="checkbox"
          checked={selectedVariantIds.has(v.id)}
          onChange={() => toggleVariantSelected(v.id)}
          onClick={e => e.stopPropagation()}
        />
      ),
    }] : []),
    { key: 'sku', header: t('bimeProductsPage.sku'), render: v => <span className="td-muted">{v.sku ?? '—'}</span> },
    {
      key: 'options',
      header: t('bimeProductsPage.options'),
      render: v => (
        <div className="role-chips">
          {v.options.map(o => <span key={o.id} className="role-badge">{o.value}</span>)}
        </div>
      ),
    },
    {
      key: 'price',
      header: t('bimeProductsPage.price'),
      render: v => v.price != null
        ? <span>{formatMoney(v.price, v.priceCurrency ?? '', i18n.language)}</span>
        : <span className="td-muted">{t('bimeProductsPage.noPriceSet')}</span>,
    },
    {
      key: 'active',
      header: t('bimeProductsPage.active'),
      render: v => (
        <span className={`status-badge ${v.isActive ? 'status-ok' : 'status-fail'}`}>
          {v.isActive ? t('bimeProductsPage.active') : t('bimeProductsPage.inactive')}
        </span>
      ),
    },
    {
      key: 'stock',
      header: t('bimeProductsPage.stock'),
      render: v => v.stock.length === 0 ? (
        <span className="td-muted">{t('bimeProductsPage.noStock')}</span>
      ) : (
        <span className="td-muted">
          {v.stock.map(s => t('bimeProductsPage.stockAt', {
            quantity: s.quantity, location: locationLookup[s.locationId]?.name ?? '—',
          })).join(', ')}
        </span>
      ),
    },
    ...(permissions.canManageBime ? [{
      key: 'actions',
      header: '',
      render: (v: ProductVariantResponse) => (
        <RowActionsMenu actions={[
          { label: t('bimeProductsPage.editVariantAction'), onClick: () => openEditVariant(v) },
          { label: t('common.actions.deactivate'), onClick: () => removeVariant(v), danger: true },
        ]} />
      ),
    }] : []),
  ]

  const variantColumnsCrossProduct: Column<ProductVariantResponse>[] = [
    {
      key: 'product',
      header: t('bimeProductsPage.name'),
      render: v => <span>{productLookup[v.productId]?.name ?? '—'}</span>,
    },
    ...variantColumns,
  ]

  const productItems = allProductsList.map(p => ({ id: p.id, label: p.name, sublabel: p.sku }))
  const productLookup: Record<string, ProductResponse> = {}
  allProductsList.forEach(p => { productLookup[p.id] = p })

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('bimeProductsPage.title')}</h1>
          <p>{t('bimeProductsPage.subtitle')}</p>
        </div>
      </div>

      <Tabs
        tabs={[
          { id: 'products', label: t('bimeProductsPage.tabProducts') },
          { id: 'variants', label: t('bimeProductsPage.tabVariants') },
        ]}
        active={activeTab}
        onChange={setActiveTab}
      >
        {activeTab === 'products' && (
          <div className="panel">
            <FilterDisclosure activeCount={productOptionFilter.length}>
              <FilterChips
                metadataDefs={metadataDefsList}
                selectedOptionIds={productOptionFilter}
                onToggle={toggleProductOptionFilter}
                onClear={() => setProductOptionFilter([])}
                matchAll={productMatchAll}
                onMatchAllChange={setProductMatchAll}
              />
            </FilterDisclosure>
            {list.state.status === 'error' && <Feedback state={list.state} />}
            <DataTable
              columns={productColumns}
              rows={products}
              rowKey={p => p.id}
              searchable
              searchText={p => `${p.sku} ${p.name}`}
              onRowClick={openVariants}
              emptyLabel={t('bimeProductsPage.emptyState')}
              headerAction={permissions.canManageBime
                ? <button className="btn btn-primary" onClick={openCreate} type="button">{t('bimeProductsPage.createAction')}</button>
                : undefined}
            />
          </div>
        )}

        {activeTab === 'variants' && (
          <div className="panel">
            <div className="field" style={{ marginBottom: '16px', maxWidth: '320px' }}>
              <label>{t('bimeProductsPage.name')}</label>
              <Combobox
                items={productItems}
                value={selectedProductId}
                onChange={id => { setSelectedProductId(id); setSelectedVariantIds(new Set()) }}
                placeholder={t('bimeProductsPage.productPlaceholder')}
              />
            </div>
            <FilterDisclosure activeCount={variantOptionFilter.length}>
              <FilterChips
                metadataDefs={metadataDefsList}
                selectedOptionIds={variantOptionFilter}
                onToggle={toggleVariantOptionFilter}
                onClear={() => setVariantOptionFilter([])}
                matchAll={variantMatchAll}
                onMatchAllChange={setVariantMatchAll}
              />
            </FilterDisclosure>
            {!selectedProductId && !searchingAcrossProducts ? (
              <div className="empty-state">{t('bimeProductsPage.selectProductHint')}</div>
            ) : (
              <>
              {searchingAcrossProducts && <p className="panel-hint">{t('bimeProductsPage.crossProductSearchHint')}</p>}
              <div className="field" style={{ marginBottom: '16px', maxWidth: '200px' }}>
                <label>{t('bimeProductsPage.viewInCurrency')}</label>
                <input
                  value={viewCurrency}
                  onChange={e => setViewCurrency(e.target.value.toUpperCase())}
                  placeholder={t('bimeProductsPage.viewInCurrencyPlaceholder')}
                  maxLength={3}
                />
              </div>
              {variants.state.status === 'error' && <Feedback state={variants.state} />}
              <DataTable
                columns={searchingAcrossProducts ? variantColumnsCrossProduct : variantColumns}
                rows={variantList}
                rowKey={v => v.id}
                emptyLabel={t('bimeProductsPage.variantsEmptyState')}
                headerAction={
                  <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                    {selectedVariantIds.size > 0 && permissions.canManageBime && (
                      <>
                        <span className="td-muted">{t('bimeProductsPage.selectedCount', { count: selectedVariantIds.size })}</span>
                        <button
                          className="btn btn-outline"
                          type="button"
                          onClick={() => { setRepriceValue(''); setRepriceModalOpen(true) }}
                        >
                          {t('bimeProductsPage.repriceSelectedAction')}
                        </button>
                      </>
                    )}
                    {permissions.canManageBime && selectedProductId && (
                      <button
                        className="btn btn-primary"
                        onClick={() => { setVariantPrice(''); setVariantPriceCurrency(''); setVariantRows([]); setVariantModalOpen(true) }}
                        type="button"
                      >
                        {t('bimeProductsPage.createVariantAction')}
                      </button>
                    )}
                  </div>
                }
              />
              </>
            )}
          </div>
        )}
      </Tabs>

      <Modal open={modalOpen} onClose={() => setModalOpen(false)} title={t(editing ? 'bimeProductsPage.editTitle' : 'bimeProductsPage.createTitle')}>
        <div className="fields">
          <div className="field">
            <label>{t('bimeProductsPage.sku')}</label>
            <input value={form.sku} onChange={e => setForm(f => ({ ...f, sku: e.target.value }))} placeholder="WIDGET-001" />
          </div>
          <div className="field">
            <label>{t('bimeProductsPage.name')}</label>
            <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="Widget" />
          </div>
          <div className="field">
            <label>{t('bimeProductsPage.description')}</label>
            <input value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={save.state.status === 'loading' || !form.sku.trim() || !form.name.trim()}
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

      <Modal
        open={assignTarget !== null}
        onClose={() => setAssignTarget(null)}
        title={assignTarget ? t('bimeProductsPage.assignMetadataTitle', { name: assignTarget.name }) : ''}
      >
        <p className="panel-hint">{t('bimeProductsPage.assignMetadataHint')}</p>
        <div className="field" style={{ marginBottom: '14px' }}>
          <AssignmentsInput value={assignRows} onChange={setAssignRows} metadataDefs={metadataDefsList} />
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={assignMetadata.state.status === 'loading' || !assignTarget}
            onClick={() => assignTarget && assignMetadata.call(() => bime.products.assignMetadata(assignTarget.id, buildAssignments(assignRows), token))}
          >
            {assignMetadata.state.status === 'loading' ? t('common.actions.loading') : t('common.actions.save')}
          </button>
        </div>
        {assignMetadata.state.status === 'error' && <Feedback state={assignMetadata.state} />}
      </Modal>

      <Modal open={variantModalOpen} onClose={() => setVariantModalOpen(false)} title={t('bimeProductsPage.createVariantTitle')}>
        <p className="panel-hint">{t('bimeProductsPage.skuAutoGeneratedHint')}</p>
        <div className="fields">
          <div className="field">
            <label>{t('bimeProductsPage.price')}</label>
            <input type="number" step="0.01" min="0" value={variantPrice} onChange={e => setVariantPrice(e.target.value)} />
          </div>
          <div className="field">
            <label>{t('bimeProductsPage.priceCurrency')}</label>
            <input
              value={variantPriceCurrency}
              onChange={e => setVariantPriceCurrency(e.target.value.toUpperCase())}
              placeholder="USD"
              maxLength={3}
              disabled={!variantPrice.trim()}
            />
          </div>
        </div>
        <div className="field" style={{ marginBottom: '14px' }}>
          <label style={{ marginBottom: '8px' }}>{t('bimeProductsPage.options')}</label>
          <AssignmentsInput value={variantRows} onChange={setVariantRows} metadataDefs={metadataDefsList} />
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={createVariant.state.status === 'loading' || (!!variantPrice.trim() && !variantPriceCurrency.trim())}
            onClick={submitVariant}
          >
            {createVariant.state.status === 'loading' ? t('common.actions.loading') : t('common.actions.create')}
          </button>
        </div>
        {createVariant.state.status === 'error' && <Feedback state={createVariant.state} />}
      </Modal>

      <Modal open={editingVariant !== null} onClose={() => setEditingVariant(null)} title={t('bimeProductsPage.editVariantTitle')}>
        {editingVariant && <p className="panel-hint">{t('bimeProductsPage.editVariantSkuHint', { sku: editingVariant.sku ?? '—' })}</p>}
        <div className="fields">
          <div className="field">
            <label>{t('bimeProductsPage.price')}</label>
            <input type="number" step="0.01" min="0" value={editVariantPrice} onChange={e => setEditVariantPrice(e.target.value)} />
          </div>
          <div className="field">
            <label>{t('bimeProductsPage.priceCurrency')}</label>
            <input
              value={editVariantPriceCurrency}
              onChange={e => setEditVariantPriceCurrency(e.target.value.toUpperCase())}
              placeholder="USD"
              maxLength={3}
              disabled={!editVariantPrice.trim()}
            />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={updateVariant.state.status === 'loading' || (!!editVariantPrice.trim() && !editVariantPriceCurrency.trim())}
            onClick={submitEditVariant}
          >
            {updateVariant.state.status === 'loading' ? t('common.actions.loading') : t('common.actions.save')}
          </button>
        </div>
        {updateVariant.state.status === 'error' && <Feedback state={updateVariant.state} />}
      </Modal>

      <Modal
        open={repriceModalOpen}
        onClose={() => setRepriceModalOpen(false)}
        title={t('bimeProductsPage.repriceSelectedTitle', { count: selectedVariantIds.size })}
      >
        <p className="panel-hint">{t('bimeProductsPage.repriceSelectedHint')}</p>
        <div className="fields">
          <div className="field">
            <label>{t('bimeProductsPage.newPrice')}</label>
            <input type="number" step="0.01" min="0" value={repriceValue} onChange={e => setRepriceValue(e.target.value)} />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={batchReprice.state.status === 'loading' || !repriceValue.trim() || Number(repriceValue) < 0}
            onClick={submitBatchReprice}
          >
            {batchReprice.state.status === 'loading' ? t('common.actions.loading') : t('common.actions.save')}
          </button>
        </div>
        {batchReprice.state.status === 'error' && <Feedback state={batchReprice.state} />}
      </Modal>
    </div>
  )
}
