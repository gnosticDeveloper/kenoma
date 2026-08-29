import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { bime } from '../api/bime'
import { formatMoney } from '../lib/money'
import { formatQuantity } from '../lib/uom'
import { useApiCall } from '../hooks/useApiCall'
import { useDebouncedEffect } from '../hooks/useDebouncedEffect'
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
  OrgUnitResponse,
  ProductMetadataAssignmentItem,
  ProductMetadataResponse,
  ProductRequest,
  ProductResponse,
  ProductVariantRequest,
  ProductVariantResponse,
  UomConversionResponse,
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

interface UomConversionRow {
  key: string
  unitId: string | null
  factor: string
  price: string
}

function UomConversionsInput({ value, onChange, baseUom, unitItems }: {
  value: UomConversionRow[]
  onChange: (rows: UomConversionRow[]) => void
  baseUom: string
  unitItems: { id: string; label: string; sublabel?: string }[]
}) {
  const { t } = useTranslation()
  return (
    <div className="roles-input">
      {value.map(row => (
        <div key={row.key} className="role-row">
          <Combobox
            items={unitItems}
            value={row.unitId}
            onChange={id => onChange(value.map(r => r.key === row.key ? { ...r, unitId: id } : r))}
            placeholder={t('bimeProductsPage.uomName')}
          />
          <input
            type="number"
            step="any"
            min="0"
            value={row.factor}
            onChange={e => onChange(value.map(r => r.key === row.key ? { ...r, factor: e.target.value } : r))}
            placeholder={t('bimeProductsPage.uomFactor')}
          />
          <input
            type="number"
            step="any"
            min="0"
            value={row.price}
            onChange={e => onChange(value.map(r => r.key === row.key ? { ...r, price: e.target.value } : r))}
            placeholder={t('bimeProductsPage.uomPriceOptional')}
          />
          <button className="btn btn-outline btn-sm" type="button" onClick={() => onChange(value.filter(r => r.key !== row.key))}>−</button>
        </div>
      ))}
      <button
        className="btn btn-outline btn-sm"
        type="button"
        onClick={() => onChange([...value, { key: newAssignmentRowKey(), unitId: null, factor: '', price: '' }])}
      >
        {t('bimeProductsPage.addUomConversion')}
      </button>
      <p className="panel-hint">{t('bimeProductsPage.uomConversionsHint', { baseUom })}</p>
    </div>
  )
}

const VIEW_CURRENCY_KEY = 'kenoma.bime.viewCurrency'
const SKU_SEARCH_DEBOUNCE_MS = 400

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

  const units = useApiCall<OrgUnitResponse[]>()
  function reloadUnits() { units.call(() => bime.units.list(token)) }
  useEffect(reloadUnits, [token])
  const unitsList = units.state.status === 'success' ? units.state.data : []
  const unitItems = unitsList.map(u => ({ id: u.id, label: u.name, sublabel: u.standard ? t('bimeProductsPage.standardUnit') : t('bimeProductsPage.customUnit') }))
  function unitNameById(id: string | null): string | undefined {
    return id ? unitsList.find(u => u.id === id)?.name : undefined
  }

  const allProducts = useApiCall<ProductResponse[]>()
  const allProductsList = allProducts.state.status === 'success' ? allProducts.state.data : []

  const [optionFilter, setOptionFilter] = useState<string[]>([])
  const [optionMatchAll, setOptionMatchAll] = useState(false)
  function toggleOptionFilter(optionId: string) {
    setOptionFilter(prev => toggleOptionId(prev, optionId))
  }
  const list = useApiCall<ProductResponse[]>()
  function reload() {
    list.call(() => bime.products.list(token, optionFilter.length ? optionFilter : undefined, optionMatchAll))
    allProducts.call(() => bime.products.list(token))
  }
  useEffect(reload, [token, optionFilter, optionMatchAll])
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
  const [skuSearch, setSkuSearch] = useState('')
  const variants = useApiCall<ProductVariantResponse[]>()
  function reloadVariants() {
    const sku = skuSearch.trim() || undefined
    if (selectedProductId) {
      variants.call(() => bime.variants.list(
        selectedProductId, token, appliedViewCurrency || undefined,
        optionFilter.length ? optionFilter : undefined, optionMatchAll, sku,
      ))
    } else if (optionFilter.length > 0 || sku) {
      variants.call(() => bime.variants.search(
        optionFilter.length ? optionFilter : undefined, token, appliedViewCurrency || undefined, optionMatchAll, sku,
      ))
    }
  }
  useDebouncedEffect(reloadVariants, [selectedProductId, appliedViewCurrency, optionFilter, optionMatchAll, skuSearch], SKU_SEARCH_DEBOUNCE_MS)
  const variantList = variants.state.status === 'success' ? variants.state.data : []
  const searchingAcrossProducts = !selectedProductId && (optionFilter.length > 0 || skuSearch.trim().length > 0)

  const [variantModalOpen, setVariantModalOpen] = useState(false)
  const [variantPrice, setVariantPrice] = useState('')
  const [variantPriceCurrency, setVariantPriceCurrency] = useState('')
  const [variantCost, setVariantCost] = useState('')
  const [variantCostCurrency, setVariantCostCurrency] = useState('')
  const [variantBaseUomId, setVariantBaseUomId] = useState<string | null>(null)
  const [variantRows, setVariantRows] = useState<AssignmentRow[]>([])
  const [variantUomRows, setVariantUomRows] = useState<UomConversionRow[]>([])
  const createVariant = useApiCall<ProductVariantResponse>()
  const deactivateVariant = useApiCall<void>()

  useEffect(() => {
    if (createVariant.state.status !== 'success') return
    setVariantModalOpen(false)
    setVariantPrice('')
    setVariantPriceCurrency('')
    setVariantCost('')
    setVariantCostCurrency('')
    setVariantBaseUomId(null)
    setVariantRows([])
    setVariantUomRows([])
    reloadVariants()
    toast.show(t('bimeProductsPage.variantCreated'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [createVariant.state])

  function submitVariant() {
    if (!selectedProductId) return
    const optionIds = buildAssignments(variantRows).flatMap(a => a.optionIds)
    const uomConversions = variantUomRows
      .filter(r => r.unitId && r.factor.trim())
      .map(r => ({ uomName: unitNameById(r.unitId)!, factor: Number(r.factor), price: r.price.trim() ? Number(r.price) : undefined }))
    const dto: ProductVariantRequest = {
      optionIds,
      price: variantPrice.trim() ? Number(variantPrice) : undefined,
      priceCurrency: variantPrice.trim() ? (variantPriceCurrency.trim() || undefined) : undefined,
      cost: variantCost.trim() ? Number(variantCost) : undefined,
      costCurrency: variantCost.trim() ? (variantCostCurrency.trim() || undefined) : undefined,
      baseUom: unitNameById(variantBaseUomId),
      uomConversions: uomConversions.length ? uomConversions : undefined,
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

  // ── Edit variant (price, cost, base unit) ──
  const [editingVariant, setEditingVariant] = useState<ProductVariantResponse | null>(null)
  const [editVariantPrice, setEditVariantPrice] = useState('')
  const [editVariantPriceCurrency, setEditVariantPriceCurrency] = useState('')
  const [editVariantCost, setEditVariantCost] = useState('')
  const [editVariantCostCurrency, setEditVariantCostCurrency] = useState('')
  const [editVariantBaseUomId, setEditVariantBaseUomId] = useState<string | null>(null)
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
    setEditVariantCost(v.cost != null ? String(v.cost) : '')
    setEditVariantCostCurrency(v.costCurrency ?? '')
    setEditVariantBaseUomId(unitsList.find(u => u.name === v.baseUom)?.id ?? null)
  }

  function submitEditVariant() {
    if (!editingVariant) return
    // optionIds isn't read by PATCH on the backend - only create uses it - so it's omitted here.
    const dto: ProductVariantRequest = {
      optionIds: [],
      price: editVariantPrice.trim() ? Number(editVariantPrice) : undefined,
      priceCurrency: editVariantPrice.trim() ? (editVariantPriceCurrency.trim() || undefined) : undefined,
      cost: editVariantCost.trim() ? Number(editVariantCost) : undefined,
      costCurrency: editVariantCost.trim() ? (editVariantCostCurrency.trim() || undefined) : undefined,
      baseUom: unitNameById(editVariantBaseUomId),
    }
    updateVariant.call(() => bime.variants.patch(editingVariant.productId, editingVariant.id, dto, token))
  }

  // ── Unit-of-measure conversions for the variant being edited ──
  const [uomConversions, setUomConversions] = useState<UomConversionResponse[]>([])
  const [newUomId, setNewUomId] = useState<string | null>(null)
  const [newUomFactor, setNewUomFactor] = useState('')
  const [newUomPrice, setNewUomPrice] = useState('')
  const uomConversionsList = useApiCall<UomConversionResponse[]>()
  const saveUomConversion = useApiCall<UomConversionResponse>()
  const deleteUomConversion = useApiCall<void>()

  useEffect(() => {
    if (editingVariant) {
      uomConversionsList.call(() => bime.uomConversions.list(editingVariant.id, token))
    } else {
      setUomConversions([])
      setNewUomId(null)
      setNewUomFactor('')
      setNewUomPrice('')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editingVariant])

  useEffect(() => {
    if (uomConversionsList.state.status === 'success') setUomConversions(uomConversionsList.state.data)
  }, [uomConversionsList.state])

  function submitNewUomConversion() {
    const uomName = unitNameById(newUomId)
    if (!editingVariant || !uomName || !newUomFactor.trim()) return
    saveUomConversion.call(() => bime.uomConversions.set(
      editingVariant.id,
      { uomName, factor: Number(newUomFactor), price: newUomPrice.trim() ? Number(newUomPrice) : undefined },
      token,
    )).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
      setNewUomId(null)
      setNewUomFactor('')
      setNewUomPrice('')
      uomConversionsList.call(() => bime.uomConversions.list(editingVariant.id, token))
    })
  }

  function removeUomConversion(uomName: string) {
    if (!editingVariant) return
    deleteUomConversion.call(() => bime.uomConversions.delete(editingVariant.id, uomName, token)).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
      uomConversionsList.call(() => bime.uomConversions.list(editingVariant.id, token))
    })
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

  const [costModalOpen, setCostModalOpen] = useState(false)
  const [batchCostValue, setBatchCostValue] = useState('')
  const batchCost = useApiCall<string[]>()

  useEffect(() => {
    if (batchCost.state.status !== 'success') return
    setCostModalOpen(false)
    setBatchCostValue('')
    setSelectedVariantIds(new Set())
    reloadVariants()
    toast.show(t('bimeProductsPage.costsUpdated'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [batchCost.state])

  function submitBatchCost() {
    const cost = Number(batchCostValue)
    const items = Array.from(selectedVariantIds).map(variantId => ({ variantId, cost }))
    batchCost.call(() => bime.variants.batchUpdateCosts({ items }, token))
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
        ? (
          <span>
            {formatMoney(v.price, v.priceCurrency ?? '', i18n.language)}
            {v.cost != null && (
              <span className="td-muted"> ({t('bimeProductsPage.margin', { margin: formatMoney(v.price - v.cost, v.priceCurrency ?? '', i18n.language) })})</span>
            )}
          </span>
        )
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
            quantity: formatQuantity(s.quantity, v.baseUom, v.uomConversions), location: locationLookup[s.locationId]?.name ?? '—',
          })).join('; ')}
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
            <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap', marginBottom: '16px' }}>
              <div className="field" style={{ maxWidth: '320px', flex: 1 }}>
                <label>{t('bimeProductsPage.name')}</label>
                <Combobox
                  items={productItems}
                  value={selectedProductId}
                  onChange={id => { setSelectedProductId(id); setSelectedVariantIds(new Set()) }}
                  placeholder={t('bimeProductsPage.productPlaceholder')}
                />
              </div>
              <div className="field" style={{ maxWidth: '280px', flex: 1 }}>
                <label>{t('bimeProductsPage.sku')}</label>
                <input
                  value={skuSearch}
                  onChange={e => setSkuSearch(e.target.value)}
                  placeholder={t('bimeProductsPage.skuSearchPlaceholder')}
                />
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
                        <button
                          className="btn btn-outline"
                          type="button"
                          onClick={() => { setBatchCostValue(''); setCostModalOpen(true) }}
                        >
                          {t('bimeProductsPage.setCostSelectedAction')}
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
          <div className="field">
            <label>{t('bimeProductsPage.cost')}</label>
            <input type="number" step="0.01" min="0" value={variantCost} onChange={e => setVariantCost(e.target.value)} />
          </div>
          <div className="field">
            <label>{t('bimeProductsPage.costCurrency')}</label>
            <input
              value={variantCostCurrency}
              onChange={e => setVariantCostCurrency(e.target.value.toUpperCase())}
              placeholder="USD"
              maxLength={3}
              disabled={!variantCost.trim()}
            />
          </div>
          <div className="field">
            <label>{t('bimeProductsPage.baseUom')}</label>
            <Combobox items={unitItems} value={variantBaseUomId} onChange={setVariantBaseUomId} placeholder={t('bimeProductsPage.baseUomDefaultPlaceholder')} />
          </div>
        </div>
        <div className="field" style={{ marginBottom: '14px' }}>
          <label style={{ marginBottom: '8px' }}>{t('bimeProductsPage.options')}</label>
          <AssignmentsInput value={variantRows} onChange={setVariantRows} metadataDefs={metadataDefsList} />
        </div>
        <div className="field" style={{ marginBottom: '14px' }}>
          <label style={{ marginBottom: '8px' }}>{t('bimeProductsPage.uomConversions')}</label>
          <UomConversionsInput value={variantUomRows} onChange={setVariantUomRows} baseUom={unitNameById(variantBaseUomId) ?? 'units'} unitItems={unitItems} />
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
          <div className="field">
            <label>{t('bimeProductsPage.cost')}</label>
            <input type="number" step="0.01" min="0" value={editVariantCost} onChange={e => setEditVariantCost(e.target.value)} />
          </div>
          <div className="field">
            <label>{t('bimeProductsPage.costCurrency')}</label>
            <input
              value={editVariantCostCurrency}
              onChange={e => setEditVariantCostCurrency(e.target.value.toUpperCase())}
              placeholder="USD"
              maxLength={3}
              disabled={!editVariantCost.trim()}
            />
          </div>
          <div className="field">
            <label>{t('bimeProductsPage.baseUom')}</label>
            <Combobox items={unitItems} value={editVariantBaseUomId} onChange={setEditVariantBaseUomId} placeholder={t('bimeProductsPage.baseUomDefaultPlaceholder')} />
          </div>
        </div>
        {editingVariant && editingVariant.price != null && editingVariant.cost != null && (
          <p className="panel-hint">
            {t('bimeProductsPage.margin', {
              margin: formatMoney(editingVariant.price - editingVariant.cost, editingVariant.priceCurrency ?? '', i18n.language),
            })}
          </p>
        )}
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

        {editingVariant && (
          <div className="field" style={{ marginTop: '18px' }}>
            <label style={{ marginBottom: '8px' }}>{t('bimeProductsPage.uomConversions')}</label>
            <p className="panel-hint">{t('bimeProductsPage.uomConversionsHint', { baseUom: editingVariant.baseUom })}</p>
            <div className="roles-input">
              {uomConversions.map(c => (
                <div key={c.id} className="role-row">
                  <span className="td-muted">
                    1 {c.uomName} = {c.factor} {editingVariant.baseUom}
                    {c.effectivePrice != null && (
                      <> · {formatMoney(c.effectivePrice, editingVariant.priceCurrency ?? '', i18n.language)}{c.price == null && ` (${t('bimeProductsPage.uomPriceDerived')})`}</>
                    )}
                  </span>
                  <button className="btn btn-outline btn-sm" type="button" onClick={() => removeUomConversion(c.uomName)}>−</button>
                </div>
              ))}
              <div className="role-row">
                <Combobox items={unitItems} value={newUomId} onChange={setNewUomId} placeholder={t('bimeProductsPage.uomName')} />
                <input
                  type="number"
                  step="any"
                  min="0"
                  value={newUomFactor}
                  onChange={e => setNewUomFactor(e.target.value)}
                  placeholder={t('bimeProductsPage.uomFactor')}
                />
                <input
                  type="number"
                  step="any"
                  min="0"
                  value={newUomPrice}
                  onChange={e => setNewUomPrice(e.target.value)}
                  placeholder={t('bimeProductsPage.uomPriceOptional')}
                />
                <button
                  className="btn btn-outline btn-sm"
                  type="button"
                  disabled={saveUomConversion.state.status === 'loading' || !newUomId || !newUomFactor.trim()}
                  onClick={submitNewUomConversion}
                >
                  +
                </button>
              </div>
            </div>
            {saveUomConversion.state.status === 'error' && <Feedback state={saveUomConversion.state} />}
          </div>
        )}
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

      <Modal
        open={costModalOpen}
        onClose={() => setCostModalOpen(false)}
        title={t('bimeProductsPage.setCostSelectedTitle', { count: selectedVariantIds.size })}
      >
        <p className="panel-hint">{t('bimeProductsPage.setCostSelectedHint')}</p>
        <div className="fields">
          <div className="field">
            <label>{t('bimeProductsPage.newCost')}</label>
            <input type="number" step="0.01" min="0" value={batchCostValue} onChange={e => setBatchCostValue(e.target.value)} />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={batchCost.state.status === 'loading' || !batchCostValue.trim() || Number(batchCostValue) < 0}
            onClick={submitBatchCost}
          >
            {batchCost.state.status === 'loading' ? t('common.actions.loading') : t('common.actions.save')}
          </button>
        </div>
        {batchCost.state.status === 'error' && <Feedback state={batchCost.state} />}
      </Modal>
    </div>
  )
}
