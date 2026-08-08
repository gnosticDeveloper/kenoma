import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { raum } from '../api/raum'
import { useApiCall } from '../hooks/useApiCall'
import { useToast } from '../components/Toast'
import { Modal } from '../components/Modal'
import { DataTable, type Column } from '../components/DataTable'
import { RowActionsMenu } from '../components/RowActionsMenu'
import { CopyButton } from '../components/CopyButton'
import { Feedback } from '../components/Feedback'
import type {
  BillingCycle,
  BillingHistoryResponse,
  BillingInfoRequest,
  CurrencyRefreshCadence,
  CurrencyRefreshMode,
  OrgRequest,
  OrgResponse,
} from '../types'

interface Props { token: string }

const EMPTY_FORM: OrgRequest = { name: '', contactEmail: '', contactName: '' }
const EMPTY_BILLING_FORM: BillingInfoRequest = {
  taxId: '', fiscalName: '', fiscalAddress: '', billingCycle: '', currency: '',
  currencyRefreshMode: '', currencyRefreshCadence: '', productPricingCurrency: '',
}
const BILLING_CYCLES: BillingCycle[] = ['MONTHLY', 'QUARTERLY', 'ANNUAL']
const CURRENCY_REFRESH_MODES: CurrencyRefreshMode[] = ['MANUAL', 'PERIODIC']
const CURRENCY_REFRESH_CADENCES: CurrencyRefreshCadence[] = ['DAILY', 'WEEKLY', 'EVERY_N_DAYS', 'MONTHLY']

function billingInfoFromOrg(org: OrgResponse): BillingInfoRequest {
  return {
    taxId: org.taxId ?? '',
    fiscalName: org.fiscalName ?? '',
    fiscalAddress: org.fiscalAddress ?? '',
    billingCycle: (org.billingCycle as BillingCycle) ?? '',
    nextInvoiceDueAt: org.nextInvoiceDueAt ?? undefined,
    currency: org.currency ?? '',
    currencyRefreshMode: (org.currencyRefreshMode as CurrencyRefreshMode) ?? '',
    currencyRefreshCadence: (org.currencyRefreshCadence as CurrencyRefreshCadence) ?? '',
    currencyRefreshIntervalDays: org.currencyRefreshIntervalDays ?? undefined,
    productPricingCurrency: org.productPricingCurrency ?? '',
  }
}

function formatAmount(row: BillingHistoryResponse): string {
  if (row.amount == null || row.currency == null) return '—'
  return `${row.currency} ${row.amount}`
}

async function triggerDownload(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.click()
  URL.revokeObjectURL(url)
}

export default function OrgsPage({ token }: Props) {
  const { t } = useTranslation()
  const toast = useToast()

  const list = useApiCall<OrgResponse[]>()
  function reload() { list.call(() => raum.orgs.list(token)) }
  useEffect(reload, [token])
  const orgs = list.state.status === 'success' ? list.state.data : []

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<OrgResponse | null>(null)
  const [form, setForm] = useState<OrgRequest>(EMPTY_FORM)
  const save = useApiCall<OrgResponse>()
  const del = useApiCall<void>()

  const [billingForm, setBillingForm] = useState<BillingInfoRequest>(EMPTY_BILLING_FORM)
  const saveBilling = useApiCall<OrgResponse>()

  const [billingEmail, setBillingEmail] = useState('')
  const sendBillingEmail = useApiCall<void>()

  useEffect(() => {
    if (save.state.status !== 'success') return
    setModalOpen(false)
    reload()
    toast.show(t(editing ? 'orgsPage.updated' : 'orgsPage.created'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [save.state])

  useEffect(() => {
    if (saveBilling.state.status !== 'success') return
    setEditing(saveBilling.state.data)
    reload()
    toast.show(t('orgsPage.billingUpdated'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [saveBilling.state])

  useEffect(() => {
    if (sendBillingEmail.state.status !== 'success') return
    toast.show(t('orgsPage.billingEmailSent'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sendBillingEmail.state])

  function openCreate() {
    setEditing(null)
    setForm(EMPTY_FORM)
    setModalOpen(true)
  }

  function openEdit(org: OrgResponse) {
    setEditing(org)
    setForm({ name: org.name, contactEmail: org.contactEmail, contactName: '' })
    setBillingForm(billingInfoFromOrg(org))
    setBillingEmail(org.billingEmail ?? '')
    setModalOpen(true)
  }

  function submit() {
    save.call(() => editing ? raum.orgs.update(editing.id, form, token) : raum.orgs.create(form, token))
  }

  function submitBillingInfo() {
    if (!editing) return
    // Backend only skips validation on null - an empty string from a reset select would be
    // rejected as an unknown enum value, so omit rather than send blank.
    const dto: BillingInfoRequest = {
      ...billingForm,
      currencyRefreshMode: billingForm.currencyRefreshMode || undefined,
      currencyRefreshCadence: billingForm.currencyRefreshMode === 'PERIODIC'
        ? (billingForm.currencyRefreshCadence || undefined)
        : undefined,
      currencyRefreshIntervalDays: billingForm.currencyRefreshMode === 'PERIODIC'
        && billingForm.currencyRefreshCadence === 'EVERY_N_DAYS'
        ? billingForm.currencyRefreshIntervalDays
        : undefined,
    }
    saveBilling.call(() => raum.orgs.updateBillingInfo(editing.id, dto, token))
  }

  function submitBillingEmail() {
    if (!editing || !billingEmail.trim()) return
    sendBillingEmail.call(() => raum.orgs.requestBillingEmailVerification(editing.id, { billingEmail: billingEmail.trim() }, token))
  }

  function remove(org: OrgResponse) {
    if (!window.confirm(t('orgsPage.deleteConfirm', { name: org.name }))) return
    del.call(() => raum.orgs.delete(org.id, token)).then(() => {
      reload()
      toast.show(t('orgsPage.deleted'))
    })
  }

  // ── Billing history ──
  const [historyTarget, setHistoryTarget] = useState<OrgResponse | null>(null)
  const history = useApiCall<BillingHistoryResponse[]>()
  const downloadInvoice = useApiCall<Blob>()
  const updateStatus = useApiCall<BillingHistoryResponse>()
  const resendInvoice = useApiCall<void>()

  function loadHistory() {
    if (historyTarget) history.call(() => raum.billingHistory.list(historyTarget.id, token))
  }

  useEffect(loadHistory, [historyTarget])

  function openHistory(org: OrgResponse) {
    setHistoryTarget(org)
  }

  function download(row: BillingHistoryResponse) {
    if (!historyTarget) return
    downloadInvoice.call(() => raum.billingHistory.downloadInvoice(historyTarget.id, row.id, token))
      .then(() => {})
  }

  function togglePaymentStatus(row: BillingHistoryResponse) {
    if (!historyTarget) return
    if (row.paymentStatus === 'PAID') {
      updateStatus.call(() => raum.billingHistory.updatePaymentStatus(historyTarget.id, row.id, { status: 'PENDING' }, token))
        .then(loadHistory)
      return
    }
    const reference = window.prompt(t('orgsPage.markPaidReferencePrompt')) ?? undefined
    updateStatus.call(() => raum.billingHistory.updatePaymentStatus(historyTarget.id, row.id, { status: 'PAID', reference }, token))
      .then(loadHistory)
  }

  function resend(row: BillingHistoryResponse) {
    if (!historyTarget) return
    resendInvoice.call(() => raum.billingHistory.resendInvoice(historyTarget.id, row.id, token))
  }

  useEffect(() => {
    if (downloadInvoice.state.status === 'success') {
      triggerDownload(downloadInvoice.state.data, `invoice-${historyTarget?.id ?? ''}.pdf`)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [downloadInvoice.state])

  useEffect(() => {
    if (resendInvoice.state.status === 'success') toast.show(t('orgsPage.invoiceResent'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resendInvoice.state])

  const columns: Column<OrgResponse>[] = [
    { key: 'name', header: t('orgsPage.name'), render: o => o.name, sortValue: o => o.name },
    { key: 'contactEmail', header: t('orgsPage.contactEmail'), render: o => o.contactEmail, sortValue: o => o.contactEmail },
    {
      key: 'billingEmail',
      header: t('orgsPage.billingEmail'),
      render: o => o.billingEmail
        ? (
          <span className={`status-badge ${o.billingEmailVerified ? 'status-ok' : 'status-fail'}`}>
            {o.billingEmail}
          </span>
        )
        : <span className="td-muted">—</span>,
    },
    {
      key: 'actions',
      header: '',
      render: o => (
        <RowActionsMenu actions={[
          { label: t('common.actions.edit'), onClick: () => openEdit(o) },
          { label: t('orgsPage.viewBillingHistory'), onClick: () => openHistory(o) },
          { label: t('common.actions.delete'), onClick: () => remove(o), danger: true },
        ]} />
      ),
    },
  ]

  const historyColumns: Column<BillingHistoryResponse>[] = [
    { key: 'dueAt', header: t('orgsPage.dueAt'), render: h => new Date(h.dueAt).toLocaleDateString() },
    { key: 'billingCycle', header: t('orgsPage.billingCycle'), render: h => t(`orgsPage.cycle.${h.billingCycle}`) },
    { key: 'amount', header: t('orgsPage.amount'), render: formatAmount },
    {
      key: 'paymentStatus',
      header: t('orgsPage.paymentStatus'),
      render: h => {
        const label = h.paymentStatus === 'PENDING' && h.overdue ? 'OVERDUE' : h.paymentStatus
        const title = h.paymentStatus === 'PAID'
          ? t('orgsPage.paidOnTooltip', {
            date: h.paidAt ? new Date(h.paidAt).toLocaleDateString() : '—',
            reference: h.paymentReference || t('orgsPage.noReference'),
          })
          : undefined
        return (
          <span className={`status-badge ${h.paymentStatus === 'PAID' ? 'status-ok' : 'status-fail'}`} title={title}>
            {t(`orgsPage.paymentStatusValue.${label}`)}
          </span>
        )
      },
    },
    {
      key: 'actions',
      header: '',
      render: h => (
        <RowActionsMenu actions={[
          { label: t('orgsPage.downloadInvoice'), onClick: () => download(h) },
          { label: t('orgsPage.resendInvoice'), onClick: () => resend(h) },
          {
            label: h.paymentStatus === 'PAID' ? t('orgsPage.markUnpaid') : t('orgsPage.markPaid'),
            onClick: () => togglePaymentStatus(h),
          },
        ]} />
      ),
    },
  ]

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('orgsPage.title')}</h1>
          <p>{t('orgsPage.subtitle')}</p>
        </div>
      </div>

      <div className="panel">
        {list.state.status === 'error' && <Feedback state={list.state} />}
        <DataTable
          columns={columns}
          rows={orgs}
          rowKey={o => o.id}
          searchable
          searchText={o => `${o.name} ${o.contactEmail}`}
          onRowClick={openEdit}
          emptyLabel={t('orgsPage.emptyState')}
          headerAction={<button className="btn btn-primary" onClick={openCreate} type="button">{t('orgsPage.createAction')}</button>}
        />
      </div>

      <Modal open={modalOpen} onClose={() => setModalOpen(false)} title={t(editing ? 'orgsPage.editTitle' : 'orgsPage.createTitle')}>
        <div className="fields">
          <div className="field">
            <label>{t('orgsPage.name')}</label>
            <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="Acme Corp" />
          </div>
          <div className="field">
            <label>{t('orgsPage.contactEmail')}</label>
            <input value={form.contactEmail} onChange={e => setForm(f => ({ ...f, contactEmail: e.target.value }))} placeholder="admin@acme.com" />
          </div>
          <div className="field">
            <label>{t('orgsPage.contactName')}</label>
            <input value={form.contactName} onChange={e => setForm(f => ({ ...f, contactName: e.target.value }))} placeholder="Jane Doe" />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={save.state.status === 'loading' || !form.name.trim() || !form.contactEmail.trim() || !form.contactName.trim()}
            onClick={submit}
          >
            {save.state.status === 'loading' ? t('common.actions.loading') : t(editing ? 'common.actions.save' : 'common.actions.create')}
          </button>
        </div>
        {save.state.status === 'error' && <Feedback state={save.state} />}

        {editing && (
          <>
            <hr className="modal-divider" />
            <h3>{t('orgsPage.billingSectionTitle')}</h3>
            <div className="fields">
              <div className="field">
                <label>{t('orgsPage.taxId')}</label>
                <input value={billingForm.taxId ?? ''} onChange={e => setBillingForm(f => ({ ...f, taxId: e.target.value }))} />
              </div>
              <div className="field">
                <label>{t('orgsPage.fiscalName')}</label>
                <input value={billingForm.fiscalName ?? ''} onChange={e => setBillingForm(f => ({ ...f, fiscalName: e.target.value }))} />
              </div>
              <div className="field">
                <label>{t('orgsPage.fiscalAddress')}</label>
                <input value={billingForm.fiscalAddress ?? ''} onChange={e => setBillingForm(f => ({ ...f, fiscalAddress: e.target.value }))} />
              </div>
              <div className="field">
                <label>{t('orgsPage.billingCycle')}</label>
                <select
                  value={billingForm.billingCycle ?? ''}
                  onChange={e => setBillingForm(f => ({ ...f, billingCycle: e.target.value as BillingCycle }))}
                >
                  <option value="">{t('orgsPage.selectBillingCycle')}</option>
                  {BILLING_CYCLES.map(c => <option key={c} value={c}>{t(`orgsPage.cycle.${c}`)}</option>)}
                </select>
              </div>
              <div className="field">
                <label>{t('orgsPage.currency')}</label>
                <input
                  value={billingForm.currency ?? ''}
                  onChange={e => setBillingForm(f => ({ ...f, currency: e.target.value.toUpperCase() }))}
                  placeholder="USD"
                  maxLength={3}
                />
                <p className="panel-hint">{t('orgsPage.currencyHint')}</p>
              </div>
              <div className="field">
                <label>{t('orgsPage.productPricingCurrency')}</label>
                <input
                  value={billingForm.productPricingCurrency ?? ''}
                  onChange={e => setBillingForm(f => ({ ...f, productPricingCurrency: e.target.value.toUpperCase() }))}
                  placeholder="ARS"
                  maxLength={3}
                />
                <p className="panel-hint">{t('orgsPage.productPricingCurrencyHint')}</p>
              </div>
              <div className="field">
                <label>{t('orgsPage.currencyRefreshMode')}</label>
                <select
                  value={billingForm.currencyRefreshMode ?? ''}
                  onChange={e => setBillingForm(f => ({
                    ...f,
                    currencyRefreshMode: e.target.value as CurrencyRefreshMode,
                    ...(e.target.value !== 'PERIODIC' ? { currencyRefreshCadence: '', currencyRefreshIntervalDays: undefined } : {}),
                  }))}
                >
                  <option value="">{t('orgsPage.selectCurrencyRefreshMode')}</option>
                  {CURRENCY_REFRESH_MODES.map(m => <option key={m} value={m}>{t(`orgsPage.refreshMode.${m}`)}</option>)}
                </select>
              </div>
              {billingForm.currencyRefreshMode === 'PERIODIC' && (
                <div className="field">
                  <label>{t('orgsPage.currencyRefreshCadence')}</label>
                  <select
                    value={billingForm.currencyRefreshCadence ?? ''}
                    onChange={e => setBillingForm(f => ({
                      ...f,
                      currencyRefreshCadence: e.target.value as CurrencyRefreshCadence,
                      ...(e.target.value !== 'EVERY_N_DAYS' ? { currencyRefreshIntervalDays: undefined } : {}),
                    }))}
                  >
                    <option value="">{t('orgsPage.selectCurrencyRefreshCadence')}</option>
                    {CURRENCY_REFRESH_CADENCES.map(c => <option key={c} value={c}>{t(`orgsPage.refreshCadence.${c}`)}</option>)}
                  </select>
                </div>
              )}
              {billingForm.currencyRefreshMode === 'PERIODIC' && billingForm.currencyRefreshCadence === 'EVERY_N_DAYS' && (
                <div className="field">
                  <label>{t('orgsPage.currencyRefreshIntervalDays')}</label>
                  <input
                    type="number"
                    min={1}
                    value={billingForm.currencyRefreshIntervalDays ?? ''}
                    onChange={e => setBillingForm(f => ({
                      ...f,
                      currencyRefreshIntervalDays: e.target.value === '' ? undefined : Number(e.target.value),
                    }))}
                  />
                </div>
              )}
            </div>
            <div className="actions">
              <button
                className="btn btn-primary"
                disabled={
                  saveBilling.state.status === 'loading'
                  || (billingForm.currencyRefreshMode === 'PERIODIC'
                    && billingForm.currencyRefreshCadence === 'EVERY_N_DAYS'
                    && !(billingForm.currencyRefreshIntervalDays && billingForm.currencyRefreshIntervalDays > 0))
                }
                onClick={submitBillingInfo}
              >
                {saveBilling.state.status === 'loading' ? t('common.actions.loading') : t('common.actions.save')}
              </button>
            </div>
            {saveBilling.state.status === 'error' && <Feedback state={saveBilling.state} />}

            <hr className="modal-divider" />
            <h3>{t('orgsPage.billingEmailSectionTitle')}</h3>
            <div className="fields">
              <div className="field">
                <label>{t('orgsPage.billingEmail')}</label>
                <input value={billingEmail} onChange={e => setBillingEmail(e.target.value)} placeholder="billing@acme.com" />
              </div>
            </div>
            {editing.billingEmail && (
              <p className="panel-hint">
                {t(editing.billingEmailVerified ? 'orgsPage.billingEmailVerified' : 'orgsPage.billingEmailUnverified', { email: editing.billingEmail })}
              </p>
            )}
            <div className="actions">
              <button
                className="btn btn-outline"
                disabled={sendBillingEmail.state.status === 'loading' || !billingEmail.trim()}
                onClick={submitBillingEmail}
              >
                {sendBillingEmail.state.status === 'loading' ? t('common.actions.loading') : t('orgsPage.sendVerification')}
              </button>
            </div>
            {sendBillingEmail.state.status === 'error' && <Feedback state={sendBillingEmail.state} />}
          </>
        )}

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
        open={historyTarget !== null}
        onClose={() => setHistoryTarget(null)}
        title={historyTarget ? t('orgsPage.billingHistoryTitle', { name: historyTarget.name }) : ''}
      >
        {history.state.status === 'error' && <Feedback state={history.state} />}
        {downloadInvoice.state.status === 'error' && <Feedback state={downloadInvoice.state} />}
        {updateStatus.state.status === 'error' && <Feedback state={updateStatus.state} />}
        {resendInvoice.state.status === 'error' && <Feedback state={resendInvoice.state} />}
        <DataTable
          columns={historyColumns}
          rows={history.state.status === 'success' ? history.state.data : []}
          rowKey={h => h.id}
          emptyLabel={t('orgsPage.billingHistoryEmptyState')}
        />
      </Modal>
    </div>
  )
}
