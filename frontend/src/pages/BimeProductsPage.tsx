import { useEffect, useMemo, useRef, useState } from 'react'
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
import { SearchIcon } from '../components/icons'
import type { Permissions } from '../auth'
import type {
  BarcodeLookupResponse,
  BarcodeSymbology,
  LocationResponse,
  OrgBarcodeSettingsResponse,
  OrgUnitResponse,
  ProductMetadataAssignmentItem,
  ProductMetadataResponse,
  ProductRequest,
  ProductResponse,
  ProductVariantRequest,
  ProductVariantResponse,
  UomConversionResponse,
  VariantBarcodeResponse,
  VariantPriceUpdate,
} from '../types'

const BARCODE_SYMBOLOGIES: BarcodeSymbology[] = ['EAN13', 'UPC_A', 'EAN8', 'CODE128', 'CODE39']

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

function triggerDownload(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
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

const EMPTY_PRODUCT_FORM: ProductRequest = { sku: '', name: '', description: '', tracksBatches: false }

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
    setForm({ sku: product.sku, name: product.name, description: product.description ?? '', tracksBatches: product.tracksBatches })
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

  function applyEditVariant(v: ProductVariantResponse) {
    setEditingVariant(v)
    setEditVariantPrice(v.price != null ? String(v.price) : '')
    setEditVariantPriceCurrency(v.priceCurrency ?? '')
    setEditVariantCost(v.cost != null ? String(v.cost) : '')
    setEditVariantCostCurrency(v.costCurrency ?? '')
    setEditVariantBaseUomId(unitsList.find(u => u.name === v.baseUom)?.id ?? null)
  }

  function openEditVariant(v: ProductVariantResponse) {
    applyEditVariant(v)
    // The row may carry prices converted to the active view currency. Editing sets *stored*
    // values, and the units section must not mix a converted base price with unconverted pack
    // prices, so re-fetch the variant in its own currency.
    if (appliedViewCurrency) {
      bime.variants.get(v.productId, v.id, token).then(applyEditVariant).catch(() => {})
    }
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
  const [uomEditing, setUomEditing] = useState<string | null>(null)   // uomName currently in edit mode
  const [uomEditForm, setUomEditForm] = useState({ factor: '', price: '' })
  const [uomAddOpen, setUomAddOpen] = useState(false)
  const [newUomId, setNewUomId] = useState<string | null>(null)
  const [newUomFactor, setNewUomFactor] = useState('')
  const [newUomPrice, setNewUomPrice] = useState('')
  const uomConversionsList = useApiCall<UomConversionResponse[]>()
  const saveUomConversion = useApiCall<UomConversionResponse>()
  const deleteUomConversion = useApiCall<void>()

  function resetUomAdd() {
    setUomAddOpen(false)
    setNewUomId(null)
    setNewUomFactor('')
    setNewUomPrice('')
  }

  useEffect(() => {
    if (editingVariant) {
      uomConversionsList.call(() => bime.uomConversions.list(editingVariant.id, token))
    } else {
      setUomConversions([])
      setUomEditing(null)
      resetUomAdd()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editingVariant])

  useEffect(() => {
    if (uomConversionsList.state.status === 'success') setUomConversions(uomConversionsList.state.data)
  }, [uomConversionsList.state])

  function refreshUomConversions() {
    if (editingVariant) uomConversionsList.call(() => bime.uomConversions.list(editingVariant.id, token))
  }

  function startEditUom(c: UomConversionResponse) {
    setUomAddOpen(false)
    setUomEditing(c.uomName)
    setUomEditForm({ factor: String(c.factor), price: c.price != null ? String(c.price) : '' })
  }

  function saveEditUom() {
    if (!editingVariant || !uomEditing) return
    if (!uomEditForm.factor.trim() || Number(uomEditForm.factor) <= 0) return
    saveUomConversion.call(() => bime.uomConversions.set(
      editingVariant.id,
      { uomName: uomEditing, factor: Number(uomEditForm.factor), price: uomEditForm.price.trim() ? Number(uomEditForm.price) : undefined },
      token,
    )).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
      toast.show(t('bimeProductsPage.uomSaved', { unit: uomEditing }))
      setUomEditing(null)
      refreshUomConversions()
    })
  }

  function submitNewUomConversion() {
    const uomName = unitNameById(newUomId)
    if (!editingVariant || !uomName || !newUomFactor.trim()) return
    saveUomConversion.call(() => bime.uomConversions.set(
      editingVariant.id,
      { uomName, factor: Number(newUomFactor), price: newUomPrice.trim() ? Number(newUomPrice) : undefined },
      token,
    )).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
      resetUomAdd()
      refreshUomConversions()
    })
  }

  function removeUomConversion(uomName: string) {
    if (!editingVariant) return
    deleteUomConversion.call(() => bime.uomConversions.delete(editingVariant.id, uomName, token)).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
      if (uomEditing === uomName) setUomEditing(null)
      refreshUomConversions()
    })
  }

  // ── Barcodes for the variant being edited ──
  const [variantBarcodes, setVariantBarcodes] = useState<VariantBarcodeResponse[]>([])
  const [newBarcodeValue, setNewBarcodeValue] = useState('')
  const [newBarcodeSymbology, setNewBarcodeSymbology] = useState<BarcodeSymbology>('EAN13')
  const [newBarcodeUom, setNewBarcodeUom] = useState('')
  const barcodesList = useApiCall<VariantBarcodeResponse[]>()
  const linkBarcode = useApiCall<VariantBarcodeResponse>()
  const issueBarcode = useApiCall<VariantBarcodeResponse>()
  const patchBarcode = useApiCall<VariantBarcodeResponse>()
  const removeBarcode = useApiCall<void>()

  useEffect(() => {
    if (editingVariant) {
      barcodesList.call(() => bime.barcodes.list(editingVariant.productId, editingVariant.id, token))
    } else {
      setVariantBarcodes([])
      setNewBarcodeValue('')
      setNewBarcodeSymbology('EAN13')
      setNewBarcodeUom('')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editingVariant])

  useEffect(() => {
    if (barcodesList.state.status === 'success') setVariantBarcodes(barcodesList.state.data)
  }, [barcodesList.state])

  function refreshBarcodes() {
    if (editingVariant) barcodesList.call(() => bime.barcodes.list(editingVariant.productId, editingVariant.id, token))
  }

  // Barcodes grouped by unit of measure: base unit first, then pack sizes by factor.
  const barcodeGroups = useMemo(() => {
    const byUnit = new Map<string, VariantBarcodeResponse[]>()
    for (const b of variantBarcodes) {
      const key = b.uom ?? ''
      if (!byUnit.has(key)) byUnit.set(key, [])
      byUnit.get(key)!.push(b)
    }
    const rank = (u: string) => {
      if (editingVariant && u === editingVariant.baseUom) return -1
      const c = editingVariant?.uomConversions.find(x => x.uomName === u)
      return c ? c.factor : Number.MAX_SAFE_INTEGER
    }
    return [...byUnit.entries()].sort((a, b) => rank(a[0]) - rank(b[0]))
  }, [variantBarcodes, editingVariant])

  function barcodeGroupCaption(unit: string): string {
    if (editingVariant && unit === editingVariant.baseUom) {
      return t('bimeProductsPage.barcodeUomBase', { unit })
    }
    const c = editingVariant?.uomConversions.find(x => x.uomName === unit)
    return c ? t('bimeProductsPage.barcodeUomPack', { unit, factor: c.factor }) : unit
  }

  function submitLinkBarcode() {
    if (!editingVariant || !newBarcodeValue.trim()) return
    linkBarcode.call(() => bime.barcodes.link(
      editingVariant.productId, editingVariant.id,
      { barcode: newBarcodeValue.trim(), symbology: newBarcodeSymbology, uom: newBarcodeUom || undefined }, token,
    )).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
      setNewBarcodeValue('')
      refreshBarcodes()
    })
  }

  function submitIssueBarcode() {
    if (!editingVariant) return
    issueBarcode.call(() => bime.barcodes.issue(
      editingVariant.productId, editingVariant.id, { uom: newBarcodeUom || undefined }, token,
    ))
  }

  useEffect(() => {
    if (issueBarcode.state.status === 'success') {
      toast.show(t('bimeProductsPage.barcodeIssued', { barcode: issueBarcode.state.data.barcode }))
      refreshBarcodes()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [issueBarcode.state])

  function setBarcodePrimary(b: VariantBarcodeResponse) {
    if (!editingVariant || b.isPrimary) return
    patchBarcode.call(() => bime.barcodes.setPrimary(
      editingVariant.productId, editingVariant.id, b.barcode, { isPrimary: true }, token,
    )).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
      refreshBarcodes()
    })
  }

  function unlinkBarcode(b: VariantBarcodeResponse) {
    if (!editingVariant) return
    removeBarcode.call(() => bime.barcodes.remove(editingVariant.productId, editingVariant.id, b.barcode, token)).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
      refreshBarcodes()
    })
  }

  // ── Scan-to-lookup (point of sale) ──
  const [scanValue, setScanValue] = useState('')
  const [scanHit, setScanHit] = useState<BarcodeLookupResponse | null>(null)
  const scanLookup = useApiCall<BarcodeLookupResponse>()
  const scanInputRef = useRef<HTMLInputElement>(null)

  function submitScan(raw?: string) {
    const value = (raw ?? scanValue).trim()
    if (!value) return
    scanLookup.call(() => bime.barcodes.lookup(value, token)).then(result => {
      if (!result.ok) { setScanHit(null); toast.show(result.message, 'error') }
    })
  }

  useEffect(() => {
    if (scanLookup.state.status === 'success') setScanHit(scanLookup.state.data)
  }, [scanLookup.state])

  // On the Products tab, capture keystrokes anywhere on the page (unless a real input/modal has
  // focus) and route them into the scan field. Lets a keyboard-wedge barcode scanner "just work"
  // without the cashier clicking the box first.
  useEffect(() => {
    if (activeTab !== 'products') return
    function onKey(e: KeyboardEvent) {
      if (e.metaKey || e.ctrlKey || e.altKey) return
      if (document.querySelector('.modal-overlay')) return
      const el = e.target as HTMLElement | null
      if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable)) return
      if (e.key === 'Enter') {
        const current = scanInputRef.current?.value.trim()
        if (current) { e.preventDefault(); submitScan(current) }
        return
      }
      if (e.key === 'Backspace') {
        e.preventDefault()
        setScanValue(v => v.slice(0, -1))
        scanInputRef.current?.focus()
        return
      }
      if (e.key.length === 1) {
        // Cancel the native insertion: focus moves to the input during this handler, so without
        // this the browser would also type the character in, duplicating the first keystroke.
        e.preventDefault()
        setScanValue(v => v + e.key)
        scanInputRef.current?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab])

  function goToScanHit() {
    if (!scanHit) return
    setSelectedProductId(scanHit.productId)
    setSkuSearch(scanHit.variant.sku ?? '')
    setSelectedVariantIds(new Set())
    setActiveTab('variants')
  }

  // ── Org barcode issuance settings ──
  const [barcodeSettingsOpen, setBarcodeSettingsOpen] = useState(false)
  const [gs1PrefixInput, setGs1PrefixInput] = useState('')
  const barcodeSettings = useApiCall<OrgBarcodeSettingsResponse>()
  const saveBarcodeSettings = useApiCall<OrgBarcodeSettingsResponse>()

  function openBarcodeSettings() {
    setBarcodeSettingsOpen(true)
    barcodeSettings.call(() => bime.barcodes.getSettings(token))
  }

  useEffect(() => {
    if (barcodeSettings.state.status === 'success') setGs1PrefixInput(barcodeSettings.state.data.gs1Prefix ?? '')
  }, [barcodeSettings.state])

  function submitBarcodeSettings() {
    saveBarcodeSettings.call(() => bime.barcodes.updateSettings({ gs1Prefix: gs1PrefixInput.trim() || null }, token)).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
      setBarcodeSettingsOpen(false)
      toast.show(t('bimeProductsPage.barcodeSettingsSaved'))
    })
  }

  // ── Barcode label sheet (PDF) ──
  const [labelTarget, setLabelTarget] = useState<ProductResponse | null>(null)
  const [labelWhich, setLabelWhich] = useState<'primary' | 'all'>('primary')
  const [labelColumns, setLabelColumns] = useState(3)
  const [labelCopies, setLabelCopies] = useState(1)
  const [labelUom, setLabelUom] = useState('')
  const [labelUnits, setLabelUnits] = useState<string[]>([])
  const labelPdf = useApiCall<Blob>()

  function openLabels(p: ProductResponse) {
    setLabelTarget(p)
    setLabelWhich('primary')
    setLabelColumns(3)
    setLabelCopies(1)
    setLabelUom('')
    setLabelUnits([])
    bime.products.get(p.id, token).then(full => {
      const units = new Set<string>()
      for (const v of full.variants ?? []) for (const b of v.barcodes ?? []) if (b.uom) units.add(b.uom)
      setLabelUnits([...units])
    }).catch(() => {})
  }

  function submitLabels() {
    if (!labelTarget) return
    const target = labelTarget
    labelPdf.call(() => bime.barcodes.labelsPdf(
      target.id,
      { which: labelWhich, columns: labelColumns, copies: labelCopies, uom: labelUom || undefined },
      token,
    )).then(result => {
      if (!result.ok) { toast.show(result.message, 'error'); return }
    })
  }

  useEffect(() => {
    if (labelPdf.state.status === 'success' && labelTarget) {
      triggerDownload(labelPdf.state.data, `barcode-labels-${labelTarget.sku}.pdf`)
      setLabelTarget(null)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [labelPdf.state])

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
          { label: t('bimeProductsPage.printLabelsAction'), onClick: () => openLabels(p) },
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
      key: 'barcodes',
      header: t('bimeProductsPage.barcodesColumn'),
      render: v => {
        const list = v.barcodes ?? []
        if (list.length === 0) return <span className="td-muted">—</span>
        const units: string[] = []
        for (const b of list) {
          const label = b.factor != null && b.factor !== 1 ? `${b.uom}×${b.factor}` : b.uom
          if (label && !units.includes(label)) units.push(label)
        }
        return (
          <span className="td-muted" title={list.map(b => b.barcode).join('\n')}>
            {units.join(' · ')}
          </span>
        )
      },
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
            <div className="barcode-lookup">
              <div className="barcode-lookup-head">
                <span>{t('bimeProductsPage.scanLookupLabel')}</span>
                {permissions.canManageBime && (
                  <button className="barcode-lookup-settings" type="button" onClick={openBarcodeSettings}>
                    {t('bimeProductsPage.barcodeSettingsAction')}
                  </button>
                )}
              </div>
              <div className="barcode-lookup-bar">
                <div className="barcode-lookup-field">
                  <SearchIcon className="barcode-lookup-icon" />
                  <input
                    ref={scanInputRef}
                    value={scanValue}
                    onChange={e => setScanValue(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); submitScan() } }}
                    placeholder={t('bimeProductsPage.scanLookupPlaceholder')}
                  />
                  {scanValue && (
                    <button
                      type="button"
                      className="barcode-lookup-clear"
                      aria-label={t('common.actions.clear')}
                      onClick={() => { setScanValue(''); setScanHit(null) }}
                    >
                      ×
                    </button>
                  )}
                </div>
                <button className="btn btn-primary btn-sm" type="button" onClick={() => submitScan()} disabled={!scanValue.trim()}>
                  {t('bimeProductsPage.scanLookupAction')}
                </button>
              </div>
              {scanHit && (
                <button
                  type="button"
                  className={`barcode-lookup-result${scanHit.variant.isActive ? '' : ' is-retired'}`}
                  onClick={goToScanHit}
                >
                  <span className="barcode-lookup-result-main">
                    <span className="barcode-lookup-result-name">
                      {scanHit.productName}
                      {!scanHit.variant.isActive && (
                        <span className="barcode-lookup-retired">{t('bimeProductsPage.scanLookupRetired')}</span>
                      )}
                    </span>
                    <span className="barcode-lookup-result-meta">
                      {scanHit.variant.sku ?? scanHit.productSku}
                      {scanHit.variant.options.length > 0 && ` · ${scanHit.variant.options.map(o => o.value).join(' / ')}`}
                      {scanHit.factor != null && scanHit.factor !== 1 && ` · ${scanHit.uom} ×${scanHit.factor}`}
                      {scanHit.batchCode && ` · ${t('bimeProductsPage.scanLookupBatch')} ${scanHit.batchCode}`}
                      {scanHit.batchExpiry && ` · ${t('bimeProductsPage.scanLookupExpiry')} ${scanHit.batchExpiry}`}
                    </span>
                    {(scanHit.recalled || scanHit.expired || scanHit.batchStatus === 'UNKNOWN') && (
                      <span className="barcode-lookup-result-meta">
                        {scanHit.recalled && (
                          <span className="barcode-lookup-retired">{t('bimeProductsPage.scanLookupRecalled')}</span>
                        )}
                        {scanHit.expired && (
                          <span className="barcode-lookup-retired">{t('bimeProductsPage.scanLookupExpired')}</span>
                        )}
                        {scanHit.batchStatus === 'UNKNOWN' && (
                          <span className="barcode-lookup-retired">{t('bimeProductsPage.scanLookupBatchUnknown')}</span>
                        )}
                      </span>
                    )}
                  </span>
                  <span className="barcode-lookup-result-price">
                    {(scanHit.packPrice ?? scanHit.variant.price) != null
                      ? formatMoney((scanHit.packPrice ?? scanHit.variant.price)!, scanHit.variant.priceCurrency ?? '', i18n.language)
                      : t('bimeProductsPage.noPriceSet')}
                  </span>
                  <span className="barcode-lookup-result-arrow" aria-hidden="true">→</span>
                </button>
              )}
            </div>
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
                    {selectedProductId && (
                      <button
                        className="btn btn-outline"
                        type="button"
                        onClick={() => { const p = productLookup[selectedProductId]; if (p) openLabels(p) }}
                      >
                        {t('bimeProductsPage.printLabelsAction')}
                      </button>
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
          <div className="field field-checkbox">
            <label>
              <input
                type="checkbox"
                checked={form.tracksBatches ?? false}
                onChange={e => setForm(f => ({ ...f, tracksBatches: e.target.checked }))}
              />
              {' '}{t('bimeProductsPage.tracksBatches')}
            </label>
            <p className="panel-hint">{t('bimeProductsPage.tracksBatchesHint')}</p>
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
            <label style={{ marginBottom: '4px' }}>{t('bimeProductsPage.uomConversions')}</label>
            <p className="panel-hint">{t('bimeProductsPage.uomConversionsHint', { baseUom: editingVariant.baseUom })}</p>

            <div className="uom-list">
              <div className="uom-row">
                <span className="uom-name">{editingVariant.baseUom}</span>
                <span className="uom-factor">×1</span>
                <span className="uom-eff">
                  {editingVariant.price != null
                    ? formatMoney(editingVariant.price, editingVariant.priceCurrency ?? '', i18n.language)
                    : <span className="td-muted">{t('bimeProductsPage.noPriceSet')}</span>}
                </span>
                <span className="uom-tag">{t('bimeProductsPage.uomBaseUnitTag')}</span>
              </div>

              {uomConversions.map(c => uomEditing === c.uomName ? (
                <div key={c.id} className="uom-row uom-row-edit">
                  <div className="uom-edit-head">
                    <span className="uom-name">{c.uomName}</span>
                  </div>
                  <div className="uom-edit-grid">
                    <label>
                      <span>{t('bimeProductsPage.uomFactorLabel', { base: editingVariant.baseUom })}</span>
                      <input
                        type="number" step="any" min="0"
                        value={uomEditForm.factor}
                        onChange={e => setUomEditForm(f => ({ ...f, factor: e.target.value }))}
                      />
                    </label>
                    <label>
                      <span>{t('bimeProductsPage.uomPriceLabel')}</span>
                      <input
                        type="number" step="any" min="0"
                        value={uomEditForm.price}
                        placeholder={t('bimeProductsPage.uomPriceDerivedShort')}
                        onChange={e => setUomEditForm(f => ({ ...f, price: e.target.value }))}
                      />
                    </label>
                  </div>
                  <div className="uom-edit-actions">
                    <button className="btn btn-outline btn-sm" type="button" onClick={() => setUomEditing(null)}>
                      {t('common.actions.cancel')}
                    </button>
                    <button
                      className="btn btn-primary btn-sm"
                      type="button"
                      disabled={saveUomConversion.state.status === 'loading' || !uomEditForm.factor.trim() || Number(uomEditForm.factor) <= 0}
                      onClick={saveEditUom}
                    >
                      {t('common.actions.save')}
                    </button>
                  </div>
                </div>
              ) : (
                <div key={c.id} className="uom-row">
                  <span className="uom-name">{c.uomName}</span>
                  <span className="uom-factor">×{c.factor}</span>
                  <span className="uom-eff">
                    {c.effectivePrice != null
                      ? formatMoney(c.effectivePrice, editingVariant.priceCurrency ?? '', i18n.language)
                      : '—'}
                  </span>
                  <span className="uom-tag">
                    {c.price != null ? t('bimeProductsPage.uomPriceFlat') : t('bimeProductsPage.uomPriceDerived')}
                  </span>
                  <button className="uom-editbtn" type="button" onClick={() => startEditUom(c)}>
                    {t('common.actions.edit')}
                  </button>
                  <button className="bc-remove" type="button" aria-label={t('common.actions.delete')} onClick={() => removeUomConversion(c.uomName)}>×</button>
                </div>
              ))}

              {uomAddOpen ? (
                <div className="uom-row uom-row-edit">
                  <div className="uom-edit-grid uom-edit-grid-add">
                    <label>
                      <span>{t('bimeProductsPage.uomName')}</span>
                      <Combobox items={unitItems} value={newUomId} onChange={setNewUomId} placeholder={t('bimeProductsPage.uomName')} />
                    </label>
                    <label>
                      <span>{t('bimeProductsPage.uomFactorLabel', { base: editingVariant.baseUom })}</span>
                      <input type="number" step="any" min="0" value={newUomFactor} onChange={e => setNewUomFactor(e.target.value)} />
                    </label>
                    <label>
                      <span>{t('bimeProductsPage.uomPriceLabel')}</span>
                      <input type="number" step="any" min="0" value={newUomPrice} placeholder={t('bimeProductsPage.uomPriceDerivedShort')} onChange={e => setNewUomPrice(e.target.value)} />
                    </label>
                  </div>
                  <div className="uom-edit-actions">
                    <button className="btn btn-outline btn-sm" type="button" onClick={resetUomAdd}>{t('common.actions.cancel')}</button>
                    <button
                      className="btn btn-primary btn-sm"
                      type="button"
                      disabled={saveUomConversion.state.status === 'loading' || !newUomId || !newUomFactor.trim()}
                      onClick={submitNewUomConversion}
                    >
                      {t('bimeProductsPage.addUomConversion')}
                    </button>
                  </div>
                </div>
              ) : (
                <button className="uom-add-btn" type="button" onClick={() => { setUomEditing(null); setUomAddOpen(true) }}>
                  {t('bimeProductsPage.addUomConversion')}
                </button>
              )}
            </div>
            {saveUomConversion.state.status === 'error' && <Feedback state={saveUomConversion.state} />}
          </div>
        )}

        {editingVariant && (
          <div className="bc" style={{ marginTop: '18px' }}>
            <div className="bc-head">
              <span className="bc-title">{t('bimeProductsPage.barcodes')}</span>
              <button
                className="btn btn-outline btn-sm"
                type="button"
                disabled={issueBarcode.state.status === 'loading'}
                onClick={submitIssueBarcode}
              >
                {t('bimeProductsPage.barcodeIssueAction')}
              </button>
            </div>
            <p className="panel-hint bc-hint">{t('bimeProductsPage.barcodesHint')}</p>

            {variantBarcodes.length === 0
              ? <p className="bc-empty">{t('bimeProductsPage.barcodesNone')}</p>
              : (
                <div className="bc-list">
                  {barcodeGroups.map(([unit, items]) => (
                    <div key={unit || 'base'} className="bc-group">
                      <div className="bc-group-head">{barcodeGroupCaption(unit)}</div>
                      {items.map(b => (
                        <div key={b.id} className={`bc-item${b.isPrimary ? ' is-primary' : ''}`}>
                          <button
                            type="button"
                            className="bc-star"
                            aria-pressed={b.isPrimary}
                            title={b.isPrimary ? t('bimeProductsPage.barcodePrimary') : t('bimeProductsPage.barcodeMakePrimary')}
                            onClick={() => setBarcodePrimary(b)}
                          >
                            {b.isPrimary ? '★' : '☆'}
                          </button>
                          <span className="bc-body">
                            <code className="bc-value">{b.barcode}</code>
                            <span className="bc-meta">
                              {b.symbology} · {b.source === 'ISSUED' ? t('bimeProductsPage.barcodeSourceIssued') : t('bimeProductsPage.barcodeSourceProvider')}
                            </span>
                          </span>
                          <button
                            className="bc-remove"
                            type="button"
                            aria-label={t('common.actions.delete')}
                            onClick={() => unlinkBarcode(b)}
                          >
                            ×
                          </button>
                        </div>
                      ))}
                    </div>
                  ))}
                </div>
              )}

            <div className="bc-add">
              <input
                className="bc-add-value"
                value={newBarcodeValue}
                onChange={e => setNewBarcodeValue(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter' && newBarcodeValue.trim()) { e.preventDefault(); submitLinkBarcode() } }}
                placeholder={t('bimeProductsPage.barcodeValuePlaceholder')}
              />
              <select value={newBarcodeSymbology} onChange={e => setNewBarcodeSymbology(e.target.value as BarcodeSymbology)}>
                {BARCODE_SYMBOLOGIES.map(s => <option key={s} value={s}>{s}</option>)}
              </select>
              {editingVariant && (
                <select value={newBarcodeUom} onChange={e => setNewBarcodeUom(e.target.value)}>
                  <option value="">{t('bimeProductsPage.barcodeUomBase', { unit: editingVariant.baseUom })}</option>
                  {editingVariant.uomConversions.map(c => (
                    <option key={c.uomName} value={c.uomName}>
                      {t('bimeProductsPage.barcodeUomPack', { unit: c.uomName, factor: c.factor })}
                    </option>
                  ))}
                </select>
              )}
              <button
                className="btn btn-outline btn-sm"
                type="button"
                disabled={linkBarcode.state.status === 'loading' || !newBarcodeValue.trim()}
                onClick={submitLinkBarcode}
              >
                {t('bimeProductsPage.barcodeLinkAction')}
              </button>
            </div>
            {linkBarcode.state.status === 'error' && <Feedback state={linkBarcode.state} />}
          </div>
        )}
      </Modal>

      <Modal
        open={barcodeSettingsOpen}
        onClose={() => setBarcodeSettingsOpen(false)}
        title={t('bimeProductsPage.barcodeSettingsTitle')}
      >
        <p className="panel-hint">{t('bimeProductsPage.barcodeSettingsHint')}</p>
        <div className="fields">
          <div className="field">
            <label>{t('bimeProductsPage.gs1Prefix')}</label>
            <input
              value={gs1PrefixInput}
              onChange={e => setGs1PrefixInput(e.target.value.replace(/\D/g, ''))}
              placeholder="5012345"
              maxLength={11}
            />
          </div>
          {barcodeSettings.state.status === 'success' && (
            <p className="panel-hint">{t('bimeProductsPage.nextSequence', { n: barcodeSettings.state.data.nextSequence })}</p>
          )}
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={saveBarcodeSettings.state.status === 'loading'}
            onClick={submitBarcodeSettings}
          >
            {saveBarcodeSettings.state.status === 'loading' ? t('common.actions.loading') : t('common.actions.save')}
          </button>
        </div>
        {saveBarcodeSettings.state.status === 'error' && <Feedback state={saveBarcodeSettings.state} />}
      </Modal>

      <Modal
        open={labelTarget !== null}
        onClose={() => setLabelTarget(null)}
        title={labelTarget ? t('bimeProductsPage.printLabelsTitle', { name: labelTarget.name }) : ''}
      >
        <p className="panel-hint">{t('bimeProductsPage.printLabelsHint')}</p>
        <div className="fields">
          <div className="field">
            <label>{t('bimeProductsPage.labelWhich')}</label>
            <select value={labelWhich} onChange={e => setLabelWhich(e.target.value as 'primary' | 'all')}>
              <option value="primary">{t('bimeProductsPage.labelWhichPrimary')}</option>
              <option value="all">{t('bimeProductsPage.labelWhichAll')}</option>
            </select>
          </div>
          {labelUnits.length > 0 && (
            <div className="field">
              <label>{t('bimeProductsPage.labelUnit')}</label>
              <select value={labelUom} onChange={e => setLabelUom(e.target.value)}>
                <option value="">{t('bimeProductsPage.labelUnitAll')}</option>
                {labelUnits.map(u => <option key={u} value={u}>{u}</option>)}
              </select>
            </div>
          )}
          <div className="field">
            <label>{t('bimeProductsPage.labelColumns')}</label>
            <input
              type="number"
              min={1}
              max={5}
              value={labelColumns}
              onChange={e => setLabelColumns(Math.max(1, Math.min(5, Number(e.target.value) || 3)))}
            />
          </div>
          <div className="field">
            <label>{t('bimeProductsPage.labelCopies')}</label>
            <input
              type="number"
              min={1}
              max={100}
              value={labelCopies}
              onChange={e => setLabelCopies(Math.max(1, Math.min(100, Number(e.target.value) || 1)))}
            />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={labelPdf.state.status === 'loading'}
            onClick={submitLabels}
          >
            {labelPdf.state.status === 'loading' ? t('common.actions.loading') : t('bimeProductsPage.labelDownloadAction')}
          </button>
        </div>
        {labelPdf.state.status === 'error' && <Feedback state={labelPdf.state} />}
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
