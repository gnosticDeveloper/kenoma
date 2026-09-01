import { Fragment, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { bime } from '../api/bime'
import { useApiCall } from '../hooks/useApiCall'
import { useToast } from '../components/Toast'
import { Modal } from '../components/Modal'
import { DataTable, type Column } from '../components/DataTable'
import { Combobox, type ComboboxItem } from '../components/Combobox'
import { Feedback } from '../components/Feedback'
import type {
  InTransitStock,
  StockTransferResponse,
  StockTransferRequest,
  TransferStatus,
} from '../types'

const STATUSES: TransferStatus[] = [
  'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'IN_TRANSIT', 'PARTIALLY_RECEIVED', 'COMPLETED', 'CANCELLED',
]

interface VariantUnits {
  base: string
  alts: string[]
}

interface Props {
  token: string
  canManage: boolean
  canApprove: boolean
  locationItems: ComboboxItem[]
  productItems: ComboboxItem[]
  variantItemsFor: (productId: string) => ComboboxItem[]
  variantLabel: (variantId: string) => string
  locationLabel: (locationId: string) => string
  variantQuantityLabel: (variantId: string, quantity: number) => string
  variantUnits: (variantId: string) => VariantUnits
  productForVariant: (variantId: string) => string | null
}

interface FormLine {
  key: number
  productId: string | null
  variantId: string | null
  quantity: number
  uom: string
}

let lineKeySeq = 1

export default function BimeTransfersTab({
  token, canManage, canApprove,
  locationItems, productItems, variantItemsFor,
  variantLabel, locationLabel, variantQuantityLabel, variantUnits, productForVariant,
}: Props) {
  const { t } = useTranslation()
  const toast = useToast()

  const list = useApiCall<StockTransferResponse[]>()
  const inTransit = useApiCall<InTransitStock[]>()
  const [filterStatus, setFilterStatus] = useState<TransferStatus | ''>('')
  const [filterSource, setFilterSource] = useState<string | null>(null)
  const [filterDest, setFilterDest] = useState<string | null>(null)

  function reload() {
    list.call(() => bime.transfers.list(token, {
      status: filterStatus || undefined,
      sourceLocationId: filterSource ?? undefined,
      destLocationId: filterDest ?? undefined,
    }))
    inTransit.call(() => bime.transfers.inTransit(token))
  }
  useEffect(reload, [filterStatus, filterSource, filterDest]) // eslint-disable-line react-hooks/exhaustive-deps

  // ── create / edit ──
  const [formOpen, setFormOpen] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [fReference, setFReference] = useState('')
  const [fNote, setFNote] = useState('')
  const [fSource, setFSource] = useState<string | null>(null)
  const [fDest, setFDest] = useState<string | null>(null)
  const [fLines, setFLines] = useState<FormLine[]>([])
  const save = useApiCall<StockTransferResponse>()

  function openCreate() {
    setEditingId(null)
    setFReference(''); setFNote(''); setFSource(null); setFDest(null)
    setFLines([{ key: lineKeySeq++, productId: null, variantId: null, quantity: 0, uom: '' }])
    setFormOpen(true)
  }

  function openEdit(transfer: StockTransferResponse) {
    setEditingId(transfer.id)
    setFReference(transfer.reference ?? '')
    setFNote(transfer.note ?? '')
    setFSource(transfer.sourceLocationId)
    setFDest(transfer.destLocationId)
    setFLines(transfer.lines.map(l => ({
      key: lineKeySeq++,
      productId: productForVariant(l.variantId),
      variantId: l.variantId,
      quantity: l.uom && l.uomQuantity != null ? l.uomQuantity : l.qtyRequested,
      uom: l.uom ?? '',
    })))
    setFormOpen(true)
  }

  function setLine(key: number, patch: Partial<FormLine>) {
    setFLines(prev => prev.map(l => (l.key === key ? { ...l, ...patch } : l)))
  }
  function addLine() {
    setFLines(prev => [...prev, { key: lineKeySeq++, productId: null, variantId: null, quantity: 0, uom: '' }])
  }
  function removeLine(key: number) {
    setFLines(prev => prev.filter(l => l.key !== key))
  }

  const formValid = useMemo(() => {
    if (!fSource || !fDest || fSource === fDest) return false
    if (fLines.length === 0) return false
    const seen = new Set<string>()
    for (const l of fLines) {
      if (!l.variantId || l.quantity <= 0) return false
      if (seen.has(l.variantId)) return false
      seen.add(l.variantId)
    }
    return true
  }, [fSource, fDest, fLines])

  function submitForm() {
    if (!fSource || !fDest) return
    const dto: StockTransferRequest = {
      reference: fReference.trim() || undefined,
      note: fNote.trim() || undefined,
      sourceLocationId: fSource,
      destLocationId: fDest,
      lines: fLines.map(l => ({
        variantId: l.variantId as string,
        quantity: l.quantity,
        uom: l.uom || undefined,
      })),
    }
    save.call(() => editingId
      ? bime.transfers.update(editingId, dto, token)
      : bime.transfers.create(dto, token))
  }

  useEffect(() => {
    if (save.state.status !== 'success') return
    setFormOpen(false)
    if (selected && save.state.data.id === selected.id) setSelected(save.state.data)
    reload()
    toast.show(t(editingId ? 'bimeStockPage.transfers.updatedOk' : 'bimeStockPage.transfers.createdOk'))
  }, [save.state]) // eslint-disable-line react-hooks/exhaustive-deps

  // ── detail + lifecycle actions ──
  const [selected, setSelected] = useState<StockTransferResponse | null>(null)
  const action = useApiCall<StockTransferResponse>()
  const del = useApiCall<void>()

  function runAction(fn: () => Promise<StockTransferResponse>, message: string) {
    action.call(fn).then(r => {
      if (!r.ok) { toast.show(r.message, 'error'); return }
      reload()
      toast.show(message)
    })
  }
  useEffect(() => {
    if (action.state.status === 'success') setSelected(action.state.data)
  }, [action.state])

  function removeTransfer(transfer: StockTransferResponse) {
    if (!window.confirm(t('bimeStockPage.transfers.deleteConfirm'))) return
    del.call(() => bime.transfers.remove(transfer.id, token)).then(r => {
      if (!r.ok) { toast.show(r.message, 'error'); return }
      setSelected(null)
      reload()
      toast.show(t('bimeStockPage.transfers.deletedOk'))
    })
  }

  // ── receive ──
  const [receiveOpen, setReceiveOpen] = useState(false)
  const [receiveQty, setReceiveQty] = useState<Record<string, number>>({})
  const [closeShort, setCloseShort] = useState(false)
  const receive = useApiCall<StockTransferResponse>()

  function openReceive(transfer: StockTransferResponse) {
    const qty: Record<string, number> = {}
    transfer.lines.forEach(l => { qty[l.id] = Math.max(0, l.qtyDispatched - l.qtyReceived) })
    setReceiveQty(qty)
    setCloseShort(false)
    setReceiveOpen(true)
  }
  function submitReceive() {
    if (!selected) return
    receive.call(() => bime.transfers.receive(selected.id, {
      lines: selected.lines
        .filter(l => (receiveQty[l.id] ?? 0) > 0)
        .map(l => ({ lineId: l.id, qtyReceived: receiveQty[l.id] })),
      closeShort,
    }, token))
  }
  useEffect(() => {
    if (receive.state.status !== 'success') return
    setReceiveOpen(false)
    setSelected(receive.state.data)
    reload()
    toast.show(t('bimeStockPage.transfers.receivedOk'))
  }, [receive.state]) // eslint-disable-line react-hooks/exhaustive-deps

  // ── rendering ──
  const columns: Column<StockTransferResponse>[] = [
    { key: 'reference', header: t('bimeStockPage.transfers.reference'), render: tr => tr.reference || <span className="td-muted">{tr.id.slice(0, 8)}…</span> },
    { key: 'status', header: t('bimeStockPage.transfers.status'), render: tr => <span className="role-badge">{t(`bimeStockPage.transfers.statuses.${tr.status}`)}</span> },
    { key: 'route', header: t('bimeStockPage.transfers.route'), render: tr => (
      <span>{tr.sourceLocationId ? locationLabel(tr.sourceLocationId) : '—'} → {tr.destLocationId ? locationLabel(tr.destLocationId) : '—'}</span>
    ) },
    { key: 'lines', header: t('bimeStockPage.transfers.lineCount'), render: tr => tr.lines.length },
    { key: 'created', header: t('bimeStockPage.created'), render: tr => <span className="td-muted">{new Date(tr.createdAt).toLocaleString()}</span> },
  ]

  const rows = list.state.status === 'success' ? list.state.data : []

  return (
    <div className="panel">
      <p className="panel-hint">{t('bimeStockPage.transfers.hint')}</p>

      <div className="fields">
        <div className="field">
          <label>{t('bimeStockPage.transfers.filterStatus')}</label>
          <select value={filterStatus} onChange={e => setFilterStatus(e.target.value as TransferStatus | '')}>
            <option value="">{t('bimeStockPage.transfers.allStatuses')}</option>
            {STATUSES.map(s => <option key={s} value={s}>{t(`bimeStockPage.transfers.statuses.${s}`)}</option>)}
          </select>
        </div>
        <div className="field">
          <label>{t('bimeStockPage.transfers.filterSource')}</label>
          <Combobox items={locationItems} value={filterSource} onChange={setFilterSource} placeholder={t('bimeStockPage.allLocations')} />
        </div>
        <div className="field">
          <label>{t('bimeStockPage.transfers.filterDest')}</label>
          <Combobox items={locationItems} value={filterDest} onChange={setFilterDest} placeholder={t('bimeStockPage.allLocations')} />
        </div>
      </div>

      {list.state.status === 'error' && <Feedback state={list.state} />}
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={tr => tr.id}
        onRowClick={setSelected}
        emptyLabel={t('bimeStockPage.transfers.empty')}
        headerAction={
          <div style={{ display: 'flex', gap: 8 }}>
            <button className="btn btn-outline" onClick={reload} type="button">{t('common.actions.refresh')}</button>
            {canManage && (
              <button className="btn btn-primary" onClick={openCreate} type="button">{t('bimeStockPage.transfers.newAction')}</button>
            )}
          </div>
        }
      />

      {inTransit.state.status === 'success' && inTransit.state.data.length > 0 && (
        <div className="stock-locate-result" style={{ marginTop: 16 }}>
          <div className="stock-locate-head">
            <span className="stock-locate-name">{t('bimeStockPage.transfers.inTransitTitle')}</span>
          </div>
          <ul className="stock-locate-list">
            {inTransit.state.data.map(r => (
              <li key={`${r.variantId}-${r.destLocationId}`}>
                <span className="stock-locate-loc">{variantLabel(r.variantId)} → {locationLabel(r.destLocationId)}</span>
                <span className="stock-locate-qty">{variantQuantityLabel(r.variantId, r.quantity)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* create / edit modal */}
      <Modal open={formOpen} onClose={() => setFormOpen(false)} title={t(editingId ? 'bimeStockPage.transfers.editTitle' : 'bimeStockPage.transfers.newTitle')}>
        <div className="fields">
          <div className="field">
            <label>{t('bimeStockPage.transfers.reference')}</label>
            <input value={fReference} onChange={e => setFReference(e.target.value)} placeholder={t('bimeStockPage.transfers.referencePlaceholder')} />
          </div>
          <div className="field">
            <label>{t('bimeStockPage.transfers.source')}</label>
            <Combobox items={locationItems} value={fSource} onChange={setFSource} placeholder={t('bimeStockPage.locationPlaceholder')} />
          </div>
          <div className="field">
            <label>{t('bimeStockPage.transfers.dest')}</label>
            <Combobox items={locationItems} value={fDest} onChange={setFDest} placeholder={t('bimeStockPage.locationPlaceholder')} />
          </div>
          <div className="field">
            <label>{t('bimeStockPage.note')}</label>
            <input value={fNote} onChange={e => setFNote(e.target.value)} placeholder={t('bimeStockPage.notePlaceholder')} />
          </div>
        </div>

        {fSource && fDest && fSource === fDest && (
          <p className="panel-hint feedback-error">{t('bimeStockPage.transfers.sameLocation')}</p>
        )}

        <div className="transfer-lines">
          <div className="transfer-lines-head">
            <span>{t('bimeStockPage.transfers.lines')}</span>
            <button className="btn btn-outline btn-sm" type="button" onClick={addLine}>{t('bimeStockPage.transfers.addLine')}</button>
          </div>
          {fLines.map((line, idx) => {
            const units = line.variantId ? variantUnits(line.variantId) : null
            return (
              <div key={line.key} className="transfer-line">
                <div className="transfer-line-top">
                  <span className="transfer-line-num">{idx + 1}</span>
                  <button
                    className="transfer-line-remove"
                    type="button"
                    onClick={() => removeLine(line.key)}
                    disabled={fLines.length === 1}
                    aria-label={t('bimeStockPage.transfers.removeLine')}
                  >
                    ×
                  </button>
                </div>
                <Combobox
                  items={productItems}
                  value={line.productId}
                  onChange={id => setLine(line.key, { productId: id, variantId: null, uom: '' })}
                  placeholder={t('bimeStockPage.productPlaceholder')}
                />
                <Combobox
                  items={line.productId ? variantItemsFor(line.productId) : []}
                  value={line.variantId}
                  onChange={id => setLine(line.key, { variantId: id, uom: '' })}
                  placeholder={t('bimeStockPage.variantPlaceholder')}
                  disabled={!line.productId}
                />
                <div className="transfer-line-qty">
                  <input
                    type="number"
                    step="any"
                    min={0}
                    value={line.quantity}
                    disabled={!line.variantId}
                    onChange={e => setLine(line.key, { quantity: parseFloat(e.target.value) || 0 })}
                  />
                  <select
                    value={line.uom}
                    onChange={e => setLine(line.key, { uom: e.target.value })}
                    disabled={!line.variantId}
                  >
                    <option value="">{units ? units.base : t('bimeStockPage.unit')}</option>
                    {units?.alts.map(u => <option key={u} value={u}>{u}</option>)}
                  </select>
                </div>
              </div>
            )
          })}
        </div>

        <div className="actions">
          <button className="btn btn-primary" disabled={!formValid || save.state.status === 'loading'} onClick={submitForm}>
            {save.state.status === 'loading' ? t('common.actions.loading') : t(editingId ? 'common.actions.save' : 'common.actions.create')}
          </button>
        </div>
        {save.state.status === 'error' && <Feedback state={save.state} />}
      </Modal>

      {/* detail modal */}
      <Modal open={selected != null} onClose={() => setSelected(null)} title={selected?.reference || t('bimeStockPage.transfers.detailTitle')}>
        {selected && (
          <>
            <div className="detail-grid">
              <div><span className="td-muted">{t('bimeStockPage.transfers.status')}</span><div><span className="role-badge">{t(`bimeStockPage.transfers.statuses.${selected.status}`)}</span></div></div>
              <div><span className="td-muted">{t('bimeStockPage.transfers.route')}</span><div>{selected.sourceLocationId ? locationLabel(selected.sourceLocationId) : '—'} → {selected.destLocationId ? locationLabel(selected.destLocationId) : '—'}</div></div>
              {selected.note && <div><span className="td-muted">{t('bimeStockPage.note')}</span><div>{selected.note}</div></div>}
              <div><span className="td-muted">{t('bimeStockPage.created')}</span><div>{new Date(selected.createdAt).toLocaleString()}</div></div>
              {selected.dispatchedAt && <div><span className="td-muted">{t('bimeStockPage.transfers.dispatchedAt')}</span><div>{new Date(selected.dispatchedAt).toLocaleString()}</div></div>}
              {selected.completedAt && <div><span className="td-muted">{t('bimeStockPage.transfers.completedAt')}</span><div>{new Date(selected.completedAt).toLocaleString()}</div></div>}
            </div>

            <table className="mini-table">
              <thead>
                <tr>
                  <th>{t('bimeStockPage.variant')}</th>
                  <th>{t('bimeStockPage.transfers.requested')}</th>
                  <th>{t('bimeStockPage.transfers.dispatched')}</th>
                  <th>{t('bimeStockPage.transfers.receivedOk')}</th>
                  <th>{t('bimeStockPage.transfers.inTransitCol')}</th>
                </tr>
              </thead>
              <tbody>
                {selected.lines.map(l => (
                  <Fragment key={l.id}>
                    <tr>
                      <td>{variantLabel(l.variantId)}</td>
                      <td>{variantQuantityLabel(l.variantId, l.qtyRequested)}</td>
                      <td>{variantQuantityLabel(l.variantId, l.qtyDispatched)}</td>
                      <td>{variantQuantityLabel(l.variantId, l.qtyReceived)}</td>
                      <td>{l.qtyInTransit > 0 ? variantQuantityLabel(l.variantId, l.qtyInTransit) : '—'}</td>
                    </tr>
                    {l.batches && l.batches.length > 0 && (
                      <tr className="transfer-line-lots">
                        <td colSpan={5}>
                          <div className="lot-note">{t('bimeStockPage.transfers.lots')}</div>
                          <table className="lot-subtable">
                            <thead>
                              <tr>
                                <th>{t('bimeBatchesTab.code')}</th>
                                <th>{t('bimeBatchesTab.expiry')}</th>
                                <th className="num">{t('bimeStockPage.transfers.dispatched')}</th>
                                <th className="num">{t('bimeStockPage.transfers.received')}</th>
                                <th className="num">{t('bimeStockPage.transfers.inTransitCol')}</th>
                              </tr>
                            </thead>
                            <tbody>
                              {l.batches.map(b => (
                                <tr key={b.batchId}>
                                  <td>{b.batchCode}{b.status === 'RECALLED' ? ` (${t('bimeStockPage.transfers.lotRecalled')})` : ''}</td>
                                  <td>{b.expiryDate ?? '—'}</td>
                                  <td className="num">{variantQuantityLabel(l.variantId, b.qtyDispatched)}</td>
                                  <td className="num">{variantQuantityLabel(l.variantId, b.qtyReceived)}</td>
                                  <td className="num">{b.qtyInTransit > 0 ? variantQuantityLabel(l.variantId, b.qtyInTransit) : '—'}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>

            <div className="actions">
              {canManage && selected.status === 'DRAFT' && (
                <>
                  <button className="btn btn-outline" onClick={() => { openEdit(selected); setSelected(null) }}>{t('common.actions.edit')}</button>
                  <button className="btn btn-outline" onClick={() => removeTransfer(selected)}>{t('common.actions.delete')}</button>
                  <button className="btn btn-primary" disabled={action.state.status === 'loading'} onClick={() => runAction(() => bime.transfers.submit(selected.id, token), t('bimeStockPage.transfers.submittedOk'))}>{t('bimeStockPage.transfers.submitAction')}</button>
                </>
              )}
              {canApprove && selected.status === 'PENDING_APPROVAL' && (
                <>
                  <button className="btn btn-outline" disabled={action.state.status === 'loading'} onClick={() => runAction(() => bime.transfers.reject(selected.id, token), t('bimeStockPage.transfers.rejectedOk'))}>{t('bimeStockPage.transfers.rejectAction')}</button>
                  <button className="btn btn-primary" disabled={action.state.status === 'loading'} onClick={() => runAction(() => bime.transfers.approve(selected.id, token), t('bimeStockPage.transfers.approvedOk'))}>{t('bimeStockPage.transfers.approveAction')}</button>
                </>
              )}
              {canManage && (selected.status === 'PENDING_APPROVAL' || selected.status === 'APPROVED') && (
                <button className="btn btn-outline" disabled={action.state.status === 'loading'} onClick={() => runAction(() => bime.transfers.cancel(selected.id, token), t('bimeStockPage.transfers.cancelledOk'))}>{t('bimeStockPage.transfers.cancelAction')}</button>
              )}
              {canManage && selected.status === 'APPROVED' && (
                <button className="btn btn-primary" disabled={action.state.status === 'loading'} onClick={() => runAction(() => bime.transfers.dispatch(selected.id, token), t('bimeStockPage.transfers.dispatchedOk'))}>{t('bimeStockPage.transfers.dispatchAction')}</button>
              )}
              {canManage && (selected.status === 'IN_TRANSIT' || selected.status === 'PARTIALLY_RECEIVED') && (
                <button className="btn btn-primary" onClick={() => openReceive(selected)}>{t('bimeStockPage.transfers.receiveAction')}</button>
              )}
            </div>
            {action.state.status === 'error' && <Feedback state={action.state} />}
          </>
        )}
      </Modal>

      {/* receive modal */}
      <Modal open={receiveOpen} onClose={() => setReceiveOpen(false)} title={t('bimeStockPage.transfers.receiveTitle')}>
        {selected && (
          <div className="transfer-receive">
            <p className="panel-hint">{t('bimeStockPage.transfers.receiveHint')}</p>
            <table className="mini-table">
              <thead>
                <tr>
                  <th>{t('bimeStockPage.variant')}</th>
                  <th className="num">{t('bimeStockPage.transfers.inTransitCol')}</th>
                  <th className="num">{t('bimeStockPage.transfers.receiveNow')}</th>
                </tr>
              </thead>
              <tbody>
                {selected.lines.map(l => {
                  const outstanding = Math.max(0, l.qtyDispatched - l.qtyReceived)
                  const lots = (l.batches ?? []).filter(b => b.qtyInTransit > 0)
                  return (
                    <Fragment key={l.id}>
                      <tr>
                        <td>{variantLabel(l.variantId)}</td>
                        <td className="num">{variantQuantityLabel(l.variantId, outstanding)}</td>
                        <td className="num">
                          <input
                            type="number"
                            step="any"
                            min={0}
                            max={outstanding}
                            value={receiveQty[l.id] ?? 0}
                            disabled={outstanding === 0}
                            onChange={e => setReceiveQty(q => ({ ...q, [l.id]: parseFloat(e.target.value) || 0 }))}
                          />
                        </td>
                      </tr>
                      {lots.length > 0 && (
                        <tr className="transfer-line-lots">
                          <td colSpan={3}>
                            <div className="lot-note">{t('bimeStockPage.transfers.lotsInTransit')}</div>
                            <table className="lot-subtable">
                              <tbody>
                                {lots.map(b => (
                                  <tr key={b.batchId}>
                                    <td>{b.batchCode}{b.status === 'RECALLED' ? ` (${t('bimeStockPage.transfers.lotRecalled')})` : ''}</td>
                                    <td>{b.expiryDate ?? '—'}</td>
                                    <td className="num">{variantQuantityLabel(l.variantId, b.qtyInTransit)}</td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                            <p className="panel-hint">{t('bimeStockPage.transfers.lotsFefoHint')}</p>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
            <label className="transfer-close-short">
              <input type="checkbox" checked={closeShort} onChange={e => setCloseShort(e.target.checked)} />
              <span>{t('bimeStockPage.transfers.closeShort')}</span>
            </label>
            <div className="actions">
              <button className="btn btn-primary" disabled={receive.state.status === 'loading'} onClick={submitReceive}>
                {receive.state.status === 'loading' ? t('common.actions.loading') : t('bimeStockPage.transfers.receiveAction')}
              </button>
            </div>
            {receive.state.status === 'error' && <Feedback state={receive.state} />}
          </div>
        )}
      </Modal>
    </div>
  )
}
