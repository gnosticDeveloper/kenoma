import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { raum } from '../api/raum'
import { formatMoney } from '../lib/money'
import { useApiCall } from '../hooks/useApiCall'
import { useToast } from '../components/Toast'
import { Modal } from '../components/Modal'
import { Tabs } from '../components/Tabs'
import { DataTable, type Column } from '../components/DataTable'
import { Combobox } from '../components/Combobox'
import { Feedback } from '../components/Feedback'
import type {
  BasePricingRequest,
  BasePricingResponse,
  ExchangeRateRequest,
  ExchangeRateResponse,
  ModulePricingRequest,
  ModulePricingResponse,
  ServiceResponse,
} from '../types'

interface Props { token: string }

const EMPTY_BASE_FORM: BasePricingRequest = { price: 0, currency: '' }
const EMPTY_MODULE_FORM: ModulePricingRequest = { serviceId: '', price: 0, currency: '', includedInBase: false }
const EMPTY_RATE_FORM: ExchangeRateRequest = { fromCurrency: '', toCurrency: '', rate: 0 }

function toIsoOrUndefined(local: string): string | undefined {
  return local ? new Date(local).toISOString() : undefined
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString()
}

export default function PricingPage({ token }: Props) {
  const { t, i18n } = useTranslation()
  const toast = useToast()
  const [activeTab, setActiveTab] = useState('base')

  const services = useApiCall<ServiceResponse[]>()
  useEffect(() => { services.call(() => raum.services.list(token)) /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [])
  const serviceList = services.state.status === 'success' ? services.state.data : []
  const serviceItems = serviceList.map(s => ({ id: s.id, label: s.name }))
  const serviceName = (id: string) => serviceList.find(s => s.id === id)?.name ?? id

  // ── Base pricing ──
  const base = useApiCall<BasePricingResponse[]>()
  function reloadBase() { base.call(() => raum.pricing.base.list(token)) }
  useEffect(reloadBase, [token])
  const baseRows = base.state.status === 'success' ? base.state.data : []

  const [baseModalOpen, setBaseModalOpen] = useState(false)
  const [baseForm, setBaseForm] = useState<BasePricingRequest>(EMPTY_BASE_FORM)
  const [baseEffectiveFrom, setBaseEffectiveFrom] = useState('')
  const addBase = useApiCall<BasePricingResponse>()

  useEffect(() => {
    if (addBase.state.status !== 'success') return
    setBaseModalOpen(false)
    reloadBase()
    toast.show(t('pricingPage.added'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [addBase.state])

  function openAddBase() {
    setBaseForm(EMPTY_BASE_FORM)
    setBaseEffectiveFrom('')
    setBaseModalOpen(true)
  }

  function submitBase() {
    addBase.call(() => raum.pricing.base.add({ ...baseForm, effectiveFrom: toIsoOrUndefined(baseEffectiveFrom) }, token))
  }

  // ── Module pricing ──
  const modules = useApiCall<ModulePricingResponse[]>()
  function reloadModules() { modules.call(() => raum.pricing.modules.list(token)) }
  useEffect(reloadModules, [token])
  const moduleRows = modules.state.status === 'success' ? modules.state.data : []

  const [moduleModalOpen, setModuleModalOpen] = useState(false)
  const [moduleForm, setModuleForm] = useState<ModulePricingRequest>(EMPTY_MODULE_FORM)
  const [moduleEffectiveFrom, setModuleEffectiveFrom] = useState('')
  const addModule = useApiCall<ModulePricingResponse>()

  useEffect(() => {
    if (addModule.state.status !== 'success') return
    setModuleModalOpen(false)
    reloadModules()
    toast.show(t('pricingPage.added'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [addModule.state])

  function openAddModule() {
    setModuleForm(EMPTY_MODULE_FORM)
    setModuleEffectiveFrom('')
    setModuleModalOpen(true)
  }

  function submitModule() {
    addModule.call(() => raum.pricing.modules.add({ ...moduleForm, effectiveFrom: toIsoOrUndefined(moduleEffectiveFrom) }, token))
  }

  // ── Exchange rates ──
  const rates = useApiCall<ExchangeRateResponse[]>()
  function reloadRates() { rates.call(() => raum.pricing.exchangeRates.list(token)) }
  useEffect(reloadRates, [token])
  const rateRows = rates.state.status === 'success' ? rates.state.data : []

  const [rateModalOpen, setRateModalOpen] = useState(false)
  const [rateForm, setRateForm] = useState<ExchangeRateRequest>(EMPTY_RATE_FORM)
  const [rateEffectiveFrom, setRateEffectiveFrom] = useState('')
  const addRate = useApiCall<ExchangeRateResponse>()

  useEffect(() => {
    if (addRate.state.status !== 'success') return
    setRateModalOpen(false)
    reloadRates()
    toast.show(t('pricingPage.rateAdded'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [addRate.state])

  function openAddRate() {
    setRateForm(EMPTY_RATE_FORM)
    setRateEffectiveFrom('')
    setRateModalOpen(true)
  }

  function submitRate() {
    addRate.call(() => raum.pricing.exchangeRates.add({ ...rateForm, effectiveFrom: toIsoOrUndefined(rateEffectiveFrom) }, token))
  }

  const baseColumns: Column<BasePricingResponse>[] = [
    { key: 'price', header: t('pricingPage.price'), render: r => formatMoney(r.price, r.currency, i18n.language) },
    { key: 'effectiveFrom', header: t('pricingPage.effectiveFrom'), render: r => formatDate(r.effectiveFrom), sortValue: r => r.effectiveFrom },
  ]

  const moduleColumns: Column<ModulePricingResponse>[] = [
    { key: 'service', header: t('pricingPage.service'), render: r => r.serviceName ?? serviceName(r.serviceId) },
    { key: 'price', header: t('pricingPage.price'), render: r => formatMoney(r.price, r.currency, i18n.language) },
    {
      key: 'includedInBase',
      header: t('pricingPage.includedInBase'),
      render: r => (
        <span className={`status-badge ${r.includedInBase ? 'status-ok' : 'status-fail'}`}>
          {r.includedInBase ? t('pricingPage.bundled') : t('pricingPage.billed')}
        </span>
      ),
    },
    { key: 'effectiveFrom', header: t('pricingPage.effectiveFrom'), render: r => formatDate(r.effectiveFrom), sortValue: r => r.effectiveFrom },
  ]

  const rateColumns: Column<ExchangeRateResponse>[] = [
    { key: 'pair', header: t('pricingPage.rate'), render: r => `${r.fromCurrency} → ${r.toCurrency}: ${new Intl.NumberFormat(i18n.language).format(r.rate)}` },
    { key: 'effectiveFrom', header: t('pricingPage.effectiveFrom'), render: r => formatDate(r.effectiveFrom), sortValue: r => r.effectiveFrom },
  ]

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('pricingPage.title')}</h1>
          <p>{t('pricingPage.subtitle')}</p>
        </div>
      </div>

      <Tabs
        tabs={[
          { id: 'base', label: t('pricingPage.tabBase') },
          { id: 'modules', label: t('pricingPage.tabModules') },
          { id: 'exchangeRates', label: t('pricingPage.tabExchangeRates') },
        ]}
        active={activeTab}
        onChange={setActiveTab}
      >
        {activeTab === 'base' && (
          <div className="panel">
            {base.state.status === 'error' && <Feedback state={base.state} />}
            <DataTable
              columns={baseColumns}
              rows={baseRows}
              rowKey={r => r.id}
              emptyLabel={t('pricingPage.baseEmptyState')}
              headerAction={<button className="btn btn-primary" onClick={openAddBase} type="button">{t('pricingPage.addAction')}</button>}
            />
          </div>
        )}

        {activeTab === 'modules' && (
          <div className="panel">
            {modules.state.status === 'error' && <Feedback state={modules.state} />}
            <DataTable
              columns={moduleColumns}
              rows={moduleRows}
              rowKey={r => r.id}
              emptyLabel={t('pricingPage.modulesEmptyState')}
              headerAction={<button className="btn btn-primary" onClick={openAddModule} type="button">{t('pricingPage.addAction')}</button>}
            />
          </div>
        )}

        {activeTab === 'exchangeRates' && (
          <div className="panel">
            {rates.state.status === 'error' && <Feedback state={rates.state} />}
            <DataTable
              columns={rateColumns}
              rows={rateRows}
              rowKey={r => r.id}
              emptyLabel={t('pricingPage.exchangeRatesEmptyState')}
              headerAction={<button className="btn btn-primary" onClick={openAddRate} type="button">{t('pricingPage.addExchangeRateAction')}</button>}
            />
          </div>
        )}
      </Tabs>

      <Modal open={baseModalOpen} onClose={() => setBaseModalOpen(false)} title={t('pricingPage.addAction')}>
        <div className="fields">
          <div className="field">
            <label>{t('pricingPage.price')}</label>
            <input
              type="number" step="0.01" min="0"
              value={baseForm.price}
              onChange={e => setBaseForm(f => ({ ...f, price: Number(e.target.value) }))}
            />
          </div>
          <div className="field">
            <label>{t('pricingPage.currency')}</label>
            <input
              value={baseForm.currency}
              onChange={e => setBaseForm(f => ({ ...f, currency: e.target.value.toUpperCase() }))}
              placeholder="USD"
              maxLength={3}
            />
          </div>
          <div className="field">
            <label>{t('pricingPage.effectiveFrom')}</label>
            <input type="datetime-local" value={baseEffectiveFrom} onChange={e => setBaseEffectiveFrom(e.target.value)} />
            <p className="panel-hint">{t('pricingPage.effectiveFromHint')}</p>
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={addBase.state.status === 'loading' || !baseForm.currency.trim() || baseForm.price < 0}
            onClick={submitBase}
          >
            {addBase.state.status === 'loading' ? t('common.actions.loading') : t('common.actions.create')}
          </button>
        </div>
        {addBase.state.status === 'error' && <Feedback state={addBase.state} />}
      </Modal>

      <Modal open={moduleModalOpen} onClose={() => setModuleModalOpen(false)} title={t('pricingPage.addAction')}>
        <div className="fields">
          <div className="field">
            <label>{t('pricingPage.service')}</label>
            <Combobox
              items={serviceItems}
              value={moduleForm.serviceId || null}
              onChange={id => setModuleForm(f => ({ ...f, serviceId: id ?? '' }))}
              placeholder={t('pricingPage.servicePlaceholder')}
            />
          </div>
          <div className="field">
            <label>{t('pricingPage.price')}</label>
            <input
              type="number" step="0.01" min="0"
              value={moduleForm.price}
              onChange={e => setModuleForm(f => ({ ...f, price: Number(e.target.value) }))}
            />
          </div>
          <div className="field">
            <label>{t('pricingPage.currency')}</label>
            <input
              value={moduleForm.currency}
              onChange={e => setModuleForm(f => ({ ...f, currency: e.target.value.toUpperCase() }))}
              placeholder="USD"
              maxLength={3}
            />
          </div>
          <div className="field field-checkbox">
            <label>
              <input
                type="checkbox"
                checked={moduleForm.includedInBase}
                onChange={e => setModuleForm(f => ({ ...f, includedInBase: e.target.checked }))}
              />
              {' '}{t('pricingPage.includedInBase')}
            </label>
            <p className="panel-hint">{t('pricingPage.includedInBaseHint')}</p>
          </div>
          <div className="field">
            <label>{t('pricingPage.effectiveFrom')}</label>
            <input type="datetime-local" value={moduleEffectiveFrom} onChange={e => setModuleEffectiveFrom(e.target.value)} />
            <p className="panel-hint">{t('pricingPage.effectiveFromHint')}</p>
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={addModule.state.status === 'loading' || !moduleForm.serviceId || !moduleForm.currency.trim() || moduleForm.price < 0}
            onClick={submitModule}
          >
            {addModule.state.status === 'loading' ? t('common.actions.loading') : t('common.actions.create')}
          </button>
        </div>
        {addModule.state.status === 'error' && <Feedback state={addModule.state} />}
      </Modal>

      <Modal open={rateModalOpen} onClose={() => setRateModalOpen(false)} title={t('pricingPage.addExchangeRateAction')}>
        <div className="fields">
          <div className="field">
            <label>{t('pricingPage.fromCurrency')}</label>
            <input
              value={rateForm.fromCurrency}
              onChange={e => setRateForm(f => ({ ...f, fromCurrency: e.target.value.toUpperCase() }))}
              placeholder="USD"
              maxLength={3}
            />
          </div>
          <div className="field">
            <label>{t('pricingPage.toCurrency')}</label>
            <input
              value={rateForm.toCurrency}
              onChange={e => setRateForm(f => ({ ...f, toCurrency: e.target.value.toUpperCase() }))}
              placeholder="ARS"
              maxLength={3}
            />
          </div>
          <div className="field">
            <label>{t('pricingPage.rate')}</label>
            <input
              type="number" step="0.00000001" min="0"
              value={rateForm.rate}
              onChange={e => setRateForm(f => ({ ...f, rate: Number(e.target.value) }))}
            />
          </div>
          <div className="field">
            <label>{t('pricingPage.effectiveFrom')}</label>
            <input type="datetime-local" value={rateEffectiveFrom} onChange={e => setRateEffectiveFrom(e.target.value)} />
            <p className="panel-hint">{t('pricingPage.effectiveFromHint')}</p>
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={addRate.state.status === 'loading' || !rateForm.fromCurrency.trim() || !rateForm.toCurrency.trim() || rateForm.rate <= 0}
            onClick={submitRate}
          >
            {addRate.state.status === 'loading' ? t('common.actions.loading') : t('common.actions.create')}
          </button>
        </div>
        {addRate.state.status === 'error' && <Feedback state={addRate.state} />}
      </Modal>
    </div>
  )
}
