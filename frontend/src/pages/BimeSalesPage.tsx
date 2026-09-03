import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { bime } from '../api/bime'
import { useApiCall } from '../hooks/useApiCall'
import { useToast } from '../components/Toast'
import { Modal } from '../components/Modal'
import { DataTable, type Column } from '../components/DataTable'
import { Combobox } from '../components/Combobox'
import { SearchIcon } from '../components/icons'
import { Feedback } from '../components/Feedback'
import { formatMoney } from '../lib/money'
import type { Permissions } from '../auth'
import type {
  LocationResponse,
  ProductVariantResponse,
  SaleLineRequest,
  SaleResponse,
} from '../types'

interface Props {
  token: string
  permissions: Permissions
}

interface UomOption {
  name: string
  factor: number
  price: number | null
}

interface VariantInfo {
  productId: string
  productName: string
  optionsLabel: string
  sku: string | null
  baseUom: string
  price: number | null
  priceCurrency: string | null
  // [0] is always the base unit (factor 1); the rest are this variant's configured pack conversions.
  uomOptions: UomOption[]
}

interface CartLine {
  key: number
  source: 'scan' | 'manual'
  barcode?: string
  variantId: string
  label: string
  uom: string | null
  qty: number
  unitPrice: number | null
  // false only when the item had no catalog/pack price, so the till must key one in
  priceLocked: boolean
  // true when the unit is fixed by the scanned barcode; false when the cashier may change it
  unitLocked: boolean
  expired: boolean
}

let lineKeySeq = 1

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

export default function BimeSalesPage({ token, permissions }: Props) {
  const { t, i18n } = useTranslation()
  const toast = useToast()
  const locale = i18n.language
  const canSell = permissions.canSellBime

  // ── reference data ──
  const locations = useApiCall<LocationResponse[]>()
  const products = useApiCall<{ id: string; name: string }[]>()
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
    Promise.allSettled(products.state.data.map(p => bime.products.get(p.id, token))).then(results => {
      if (cancelled) return
      const byProduct: Record<string, ProductVariantResponse[]> = {}
      const lookup: Record<string, VariantInfo> = {}
      results.forEach(r => {
        if (r.status !== 'fulfilled') return
        const p = r.value
        const vs = p.variants ?? []
        byProduct[p.id] = vs
        vs.forEach(v => {
          lookup[v.id] = {
            productId: p.id,
            productName: p.name,
            optionsLabel: v.options.map(o => o.value).join(', '),
            sku: v.sku,
            baseUom: v.baseUom,
            price: v.price ?? null,
            priceCurrency: v.priceCurrency ?? null,
            uomOptions: [
              { name: v.baseUom, factor: 1, price: v.price ?? null },
              ...(v.uomConversions ?? []).map(c => ({
                name: c.uomName, factor: c.factor, price: c.effectivePrice,
              })),
            ],
          }
        })
      })
      setVariantsByProduct(byProduct)
      setVariantLookup(lookup)
    })
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [products.state.status])

  const locationLookup: Record<string, LocationResponse> = {}
  if (locations.state.status === 'success') locations.state.data.forEach(l => { locationLookup[l.id] = l })
  const locationItems = (locations.state.status === 'success' ? locations.state.data : []).map(l => ({ id: l.id, label: l.name, sublabel: l.code }))
  const productItems = (products.state.status === 'success' ? products.state.data : []).map(p => ({ id: p.id, label: p.name }))

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
    return info.optionsLabel
      ? `${info.productName} — ${info.sku ?? '—'} (${info.optionsLabel})`
      : `${info.productName} — ${info.sku ?? '—'}`
  }
  function locationLabel(locationId: string): string {
    const loc = locationLookup[locationId]
    return loc ? `${loc.name} (${loc.code})` : locationId.slice(0, 8) + '…'
  }

  // ── register state ──
  const [locationId, setLocationId] = useState<string | null>(null)
  const [cart, setCart] = useState<CartLine[]>([])
  const [cartCurrency, setCartCurrency] = useState<string | null>(null)
  const [reference, setReference] = useState('')
  const [scan, setScan] = useState('')
  const scanRef = useRef<HTMLInputElement>(null)

  const recent = useApiCall<SaleResponse[]>()
  const [receipt, setReceipt] = useState<SaleResponse | null>(null)
  const [printing, setPrinting] = useState(false)
  const [completing, setCompleting] = useState(false)
  const [completeError, setCompleteError] = useState<string | null>(null)

  // Default to the only location once it loads.
  useEffect(() => {
    if (!locationId && locationItems.length === 1) setLocationId(locationItems[0].id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locationItems.length])

  function loadRecent() {
    if (!locationId) return
    recent.call(() => bime.sales.list(token, { locationId }))
  }
  useEffect(loadRecent, [locationId]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (cart.length === 0 && cartCurrency !== null) setCartCurrency(null)
  }, [cart.length, cartCurrency])

  const money = (n: number) => formatMoney(n, cartCurrency ?? '', locale)

  function addToCart(next: Omit<CartLine, 'key'>) {
    setCart(prev => {
      const match = prev.find(l =>
        l.source === next.source &&
        l.variantId === next.variantId &&
        l.uom === next.uom &&
        l.barcode === next.barcode,
      )
      if (match) return prev.map(l => (l === match ? { ...l, qty: l.qty + next.qty } : l))
      return [...prev, { ...next, key: lineKeySeq++ }]
    })
  }

  async function submitScan(raw?: string) {
    const code = (raw ?? scan).trim()
    if (!code || !canSell) return
    setScan('')
    try {
      const r = await bime.barcodes.lookup(code, token)
      if (r.recalled) {
        toast.show(t('bimeSalesPage.recalledWarning', { name: r.productName }), 'error')
        return
      }
      if (r.variant.priceCurrency && !cartCurrency) setCartCurrency(r.variant.priceCurrency)
      addToCart({
        source: 'scan',
        barcode: r.barcode,
        variantId: r.variant.id,
        label: `${r.productName}${r.uom ? ` · ${r.uom}` : ''}`,
        uom: r.factor != null && r.factor !== 1 ? r.uom : null,
        qty: 1,
        unitPrice: r.packPrice,
        priceLocked: r.packPrice != null,
        unitLocked: true,
        expired: r.expired,
      })
      if (r.expired) toast.show(t('bimeSalesPage.expiredWarning', { name: r.productName }))
    } catch {
      toast.show(t('bimeSalesPage.notFound', { code }), 'error')
    } finally {
      scanRef.current?.focus()
    }
  }

  function onScan(e: FormEvent) {
    e.preventDefault()
    submitScan()
  }

  // A physical scanner types the code fast and sends Enter. Capture those keystrokes even when
  // the scan box isn't focused and funnel them into it, so the cashier can just scan. Mirrors
  // BimeStockPage's "scan to locate" box.
  useEffect(() => {
    if (!canSell || !locationId) return
    function onKey(e: KeyboardEvent) {
      if (e.metaKey || e.ctrlKey || e.altKey) return
      if (document.querySelector('.modal-overlay')) return
      const el = e.target as HTMLElement | null
      if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable)) return
      if (e.key === 'Enter') {
        const cur = scanRef.current?.value.trim()
        if (cur) { e.preventDefault(); submitScan(cur) }
        return
      }
      if (e.key === 'Backspace') {
        e.preventDefault(); setScan(v => v.slice(0, -1)); scanRef.current?.focus(); return
      }
      if (e.key.length === 1) {
        e.preventDefault(); setScan(v => v + e.key); scanRef.current?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canSell, locationId])

  // ── manual add ──
  const [mProduct, setMProduct] = useState<string | null>(null)
  const [mVariant, setMVariant] = useState<string | null>(null)
  function addManual() {
    if (!mVariant) return
    const info = variantLookup[mVariant]
    if (info?.priceCurrency && !cartCurrency) setCartCurrency(info.priceCurrency)
    addToCart({
      source: 'manual',
      variantId: mVariant,
      label: variantLabel(mVariant),
      uom: null,
      qty: 1,
      unitPrice: info?.price ?? null,
      priceLocked: (info?.price ?? null) != null,
      unitLocked: false,
      expired: false,
    })
    setMVariant(null)
  }

  function setQty(key: number, qty: number) {
    setCart(prev => prev.map(l => (l.key === key ? { ...l, qty: Math.max(0, qty) } : l)).filter(l => l.qty > 0))
  }
  function setPrice(key: number, price: number) {
    setCart(prev => prev.map(l => (l.key === key ? { ...l, unitPrice: Number.isFinite(price) ? price : null } : l)))
  }
  // Manually-added lines only: switch the unit and re-derive its price from the catalogue.
  function setUom(key: number, name: string) {
    setCart(prev => prev.map(l => {
      if (l.key !== key) return l
      const opts = variantLookup[l.variantId]?.uomOptions ?? []
      const opt = opts.find(o => o.name === name)
      if (!opt) return l
      const isBase = opts[0]?.name === name
      return { ...l, uom: isBase ? null : opt.name, unitPrice: opt.price, priceLocked: opt.price != null }
    }))
  }
  function removeLine(key: number) {
    setCart(prev => prev.filter(l => l.key !== key))
  }

  const subtotal = useMemo(
    () => cart.reduce((sum, l) => sum + (l.unitPrice ?? 0) * l.qty, 0),
    [cart],
  )
  const missingPrice = cart.some(l => l.unitPrice == null)
  const canComplete = canSell && !!locationId && cart.length > 0 && !missingPrice && !completing

  async function completeSale() {
    if (!canComplete || !locationId) return
    const lines: SaleLineRequest[] = cart.map(l => ({
      barcode: l.source === 'scan' ? l.barcode : undefined,
      variantId: l.source === 'manual' ? l.variantId : undefined,
      quantity: l.qty,
      uom: l.source === 'manual' && l.uom ? l.uom : undefined,
      unitPrice: l.unitPrice ?? undefined,
    }))
    setCompleting(true)
    setCompleteError(null)
    try {
      const sale = await bime.sales.create({
        locationId,
        reference: reference.trim() || undefined,
        lines,
      }, token)
      setReceipt(sale)
      toast.show(t('bimeSalesPage.recorded'))
      setCart([]); setReference('')
      loadRecent()
      scanRef.current?.focus()
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e)
      setCompleteError(message)
      toast.show(message, 'error')
    } finally {
      setCompleting(false)
    }
  }

  async function printTicket(sale: SaleResponse) {
    setPrinting(true)
    try {
      const blob = await bime.sales.ticketPdf(sale.id, token, locale.slice(0, 2))
      triggerDownload(blob, `sale-ticket-${sale.reference || sale.id.slice(0, 8)}.pdf`)
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error')
    } finally {
      setPrinting(false)
    }
  }

  const cartColumns: Column<CartLine>[] = [
    { key: 'item', header: t('bimeSalesPage.item'), render: l => l.label },
    {
      key: 'unit', header: t('bimeSalesPage.unit'),
      render: l => {
        const info = variantLookup[l.variantId]
        const opts = info?.uomOptions ?? []
        // Barcode-scanned lines carry the unit from the barcode; nothing to choose when the variant
        // has no configured pack units either.
        if (l.unitLocked || opts.length <= 1) {
          return <span className="pos-price">{l.uom ?? opts[0]?.name ?? info?.baseUom ?? '—'}</span>
        }
        return (
          <select value={l.uom ?? opts[0].name} onChange={e => setUom(l.key, e.target.value)}>
            {opts.map(o => (
              <option key={o.name} value={o.name}>
                {o.name}{o.factor !== 1 ? ` (×${o.factor})` : ''}
              </option>
            ))}
          </select>
        )
      },
    },
    {
      key: 'qty', header: t('bimeSalesPage.qty'),
      render: l => (
        <input type="number" min={0} step="any" value={l.qty}
          onChange={e => setQty(l.key, parseFloat(e.target.value) || 0)} />
      ),
    },
    {
      key: 'unitPrice', header: t('bimeSalesPage.unitPrice'),
      // Read-only: the catalog price stands. Editable only for an item with no price on file,
      // so a data gap doesn't hard-block the sale.
      render: l => l.priceLocked
        ? <span className="pos-price">{money(l.unitPrice ?? 0)}</span>
        : (
          <input type="number" min={0} step="0.01" value={l.unitPrice ?? ''}
            className={l.unitPrice == null ? 'is-invalid' : undefined}
            placeholder="0.00"
            onChange={e => setPrice(l.key, parseFloat(e.target.value))} />
        ),
    },
    { key: 'lineTotal', header: t('bimeSalesPage.lineTotal'), render: l => money((l.unitPrice ?? 0) * l.qty) },
    {
      key: 'actions', header: '',
      render: l => (
        <button className="btn btn-outline btn-sm" type="button" onClick={() => removeLine(l.key)}>
          {t('bimeSalesPage.remove')}
        </button>
      ),
    },
  ]

  const recentRows = recent.state.status === 'success' ? recent.state.data : []
  const recentColumns: Column<SaleResponse>[] = [
    { key: 'date', header: t('bimeSalesPage.colDate'), render: s => new Date(s.soldAt).toLocaleString(locale) },
    { key: 'ref', header: t('bimeSalesPage.colReference'), render: s => s.reference || '—' },
    { key: 'items', header: t('bimeSalesPage.colItems'), render: s => String(s.lines.length) },
    { key: 'total', header: t('bimeSalesPage.colTotal'), render: s => formatMoney(s.subtotal, s.currency ?? '', locale) },
    {
      key: 'view', header: '',
      render: s => (
        <div className="actions">
          <button className="btn btn-outline btn-sm" type="button" onClick={() => setReceipt(s)}>
            {t('bimeSalesPage.viewReceipt')}
          </button>
          <button className="btn btn-outline btn-sm" type="button" disabled={printing}
            onClick={() => printTicket(s)}>
            {t('bimeSalesPage.printTicket')}
          </button>
        </div>
      ),
    },
  ]

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('bimeSalesPage.title')}</h1>
          <p>{t('bimeSalesPage.subtitle')}</p>
        </div>
      </div>

      <div className="panel">
        <p className="panel-hint">{t('bimeSalesPage.hint')}</p>

        <div className="fields">
          <div className="field">
            <label>{t('bimeSalesPage.location')}</label>
            <Combobox items={locationItems} value={locationId} onChange={setLocationId}
              placeholder={t('bimeSalesPage.selectLocation')} />
          </div>
        </div>

        {locations.state.status === 'error' && <Feedback state={locations.state} />}
        {!locationId && <p className="panel-hint">{t('bimeSalesPage.locationRequired')}</p>}

        {canSell && locationId && (
          <div className="pos-register">
            <div className="pos-register-main">
              <div className="barcode-lookup">
                <div className="barcode-lookup-head">
                  <span>{t('bimeSalesPage.cart')}</span>
                </div>
                <form onSubmit={onScan} className="barcode-lookup-bar">
                  <div className="barcode-lookup-field">
                    <SearchIcon className="barcode-lookup-icon" />
                    <input ref={scanRef} autoFocus value={scan} onChange={e => setScan(e.target.value)}
                      placeholder={t('bimeSalesPage.scanPlaceholder')} />
                    {scan && (
                      <button type="button" className="barcode-lookup-clear"
                        aria-label={t('common.actions.clear')} onClick={() => setScan('')}>×</button>
                    )}
                  </div>
                  <button className="btn btn-primary btn-sm" type="submit" disabled={!scan.trim()}>
                    {t('bimeSalesPage.add')}
                  </button>
                </form>
              </div>

              <div className="pos-cart">
                <DataTable
                  columns={cartColumns}
                  rows={cart}
                  rowKey={l => String(l.key)}
                  emptyLabel={t('bimeSalesPage.cartEmpty')}
                />
              </div>

              <p className="panel-hint" style={{ margin: '14px 0 0' }}>{t('bimeSalesPage.scanHint')}</p>
              <div className="fields">
                <div className="field">
                  <label>{t('bimeSalesPage.product')}</label>
                  <Combobox items={productItems} value={mProduct}
                    onChange={id => { setMProduct(id); setMVariant(null) }}
                    placeholder={t('bimeSalesPage.addManual')} />
                </div>
                <div className="field">
                  <label>{t('bimeSalesPage.variant')}</label>
                  <Combobox items={mProduct ? variantItemsFor(mProduct) : []} value={mVariant}
                    onChange={setMVariant} placeholder={t('bimeSalesPage.variant')} disabled={!mProduct} />
                </div>
              </div>
              <div className="actions">
                <button className="btn btn-outline" type="button" disabled={!mVariant} onClick={addManual}>
                  {t('bimeSalesPage.add')}
                </button>
              </div>
            </div>

            <aside className="pos-summary">
              <h2>{t('bimeSalesPage.summary')}</h2>
              <div className="field">
                <label>{t('bimeSalesPage.reference')}</label>
                <input value={reference} onChange={e => setReference(e.target.value)}
                  placeholder={t('bimeSalesPage.referencePlaceholder')} />
              </div>
              <div className="pos-summary-row">
                <span>{t('bimeSalesPage.itemCount', { count: cart.length })}</span>
              </div>
              <div className="pos-summary-row total">
                <span>{t('bimeSalesPage.subtotal')}</span>
                <strong>{money(subtotal)}</strong>
              </div>
              {missingPrice && <p className="feedback-error">{t('bimeSalesPage.noPrice')}</p>}
              {completeError && <p className="feedback-error">{completeError}</p>}
              <button className="btn btn-primary btn-full" type="button" disabled={!canComplete} onClick={completeSale}>
                {completing ? t('bimeSalesPage.completing') : t('bimeSalesPage.complete')}
              </button>
            </aside>
          </div>
        )}
      </div>

      <div className="panel">
        <h2>{t('bimeSalesPage.recentSales')}</h2>
        {recent.state.status === 'error' && <Feedback state={recent.state} />}
        <DataTable
          columns={recentColumns}
          rows={recentRows}
          rowKey={s => s.id}
          emptyLabel={t('bimeSalesPage.recentEmpty')}
          headerAction={<button className="btn btn-outline" type="button" onClick={loadRecent}>{t('common.actions.refresh')}</button>}
        />
      </div>

      <Modal open={receipt !== null} onClose={() => setReceipt(null)}
        title={t('bimeSalesPage.receiptTitle', { ref: receipt?.reference || receipt?.id.slice(0, 8) || '' })}>
        {receipt && (
          <>
            <p className="panel-hint">
              {t('bimeSalesPage.soldAt')}: {new Date(receipt.soldAt).toLocaleString(locale)} · {locationLabel(receipt.locationId)}
            </p>
            <table className="mini-table">
              <thead>
                <tr>
                  <th>{t('bimeSalesPage.item')}</th>
                  <th className="num">{t('bimeSalesPage.qty')}</th>
                  <th className="num">{t('bimeSalesPage.unitPrice')}</th>
                  <th className="num">{t('bimeSalesPage.lineTotal')}</th>
                </tr>
              </thead>
              <tbody>
                {receipt.lines.map(l => (
                  <tr key={l.id}>
                    <td>{variantLabel(l.variantId)}{l.uom ? ` · ${l.uom}` : ''}</td>
                    <td className="num">{l.uom && l.uomQuantity != null ? l.uomQuantity : l.qtyBase}</td>
                    <td className="num">{formatMoney(l.unitPrice, receipt.currency ?? '', locale)}</td>
                    <td className="num">{formatMoney(l.lineTotal, receipt.currency ?? '', locale)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <td colSpan={3}><strong>{t('bimeSalesPage.subtotal')}</strong></td>
                  <td className="num"><strong>{formatMoney(receipt.subtotal, receipt.currency ?? '', locale)}</strong></td>
                </tr>
              </tfoot>
            </table>
            <div className="actions" style={{ marginTop: 14 }}>
              <button className="btn btn-primary" type="button" disabled={printing}
                onClick={() => receipt && printTicket(receipt)}>
                {printing ? t('bimeSalesPage.printingTicket') : t('bimeSalesPage.printTicket')}
              </button>
            </div>
          </>
        )}
      </Modal>
    </div>
  )
}
