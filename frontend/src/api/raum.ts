import type {
  BasePricingRequest,
  BasePricingResponse,
  BasicCredential,
  BillingEmailRequest,
  BillingEmailVerifyRequest,
  BillingHistoryResponse,
  BillingInfoRequest,
  Credentials,
  DrBackupResponse,
  DrBackupScope,
  ExchangeRateRequest,
  ExchangeRateResponse,
  ExportDownloadResponse,
  ExportFormat,
  ExportJobResponse,
  ExportLayout,
  ModulePricingRequest,
  ModulePricingResponse,
  OrgRequest,
  OrgResponse,
  OnboardingRequest,
  PaymentStatusUpdateRequest,
  RoleResponse,
  ServiceRequest,
  ServiceResponse,
} from '../types'
import { API_BASE_URL } from './base'
import { ApiError } from './client'
import { payload, req } from './client'

export const raum = {
  orgs: {
    create: (dto: OrgRequest, token: string) =>
      req<OrgResponse>('/orgs', { method: 'POST', ...payload(dto) }, token),
    list: (token: string) =>
      req<OrgResponse[]>('/orgs', { method: 'GET' }, token),
    get: (id: string, token: string) =>
      req<OrgResponse>(`/orgs/${id}`, { method: 'GET' }, token),
    update: (id: string, dto: OrgRequest, token: string) =>
      req<OrgResponse>(`/orgs/${id}`, { method: 'PUT', ...payload(dto) }, token),
    delete: (id: string, token: string) =>
      req<void>(`/orgs/${id}`, { method: 'DELETE' }, token),
    updateBillingInfo: (id: string, dto: BillingInfoRequest, token: string) =>
      req<OrgResponse>(`/orgs/${id}/billing-info`, { method: 'PUT', ...payload(dto) }, token),
    requestBillingEmailVerification: (id: string, dto: BillingEmailRequest, token: string) =>
      req<void>(`/orgs/${id}/billing-email`, { method: 'POST', ...payload(dto) }, token),
    confirmBillingEmail: (id: string, dto: BillingEmailVerifyRequest) =>
      req<void>(`/orgs/${id}/billing-email/confirm`, { method: 'POST', ...payload(dto) }),
    confirmContactEmail: (id: string, dto: BillingEmailVerifyRequest) =>
      req<void>(`/orgs/${id}/contact-email/confirm`, { method: 'POST', ...payload(dto) }),
    requestExport: (id: string, format: ExportFormat, layout: ExportLayout, token: string) =>
      req<ExportJobResponse>(`/orgs/${id}/export?format=${format}&layout=${layout}`, { method: 'POST' }, token),
    getExportJob: (id: string, jobId: string, token: string) =>
      req<ExportJobResponse>(`/orgs/${id}/export/${jobId}`, { method: 'GET' }, token),
    getExportDownloadLinks: (id: string, jobId: string, token: string) =>
      req<ExportDownloadResponse>(`/orgs/${id}/export/${jobId}/download`, { method: 'GET' }, token),
    downloadExportFile: async (id: string, jobId: string, index: number, token: string): Promise<Blob> => {
      const res = await fetch(`${API_BASE_URL}/orgs/${id}/export/${jobId}/download/${index}`, {
        headers: { Authorization: `Bearer ${token}` },
        credentials: 'include',
      })
      if (!res.ok) {
        const text = await res.text().catch(() => '')
        throw new ApiError(res.status, res.statusText, text)
      }
      return res.blob()
    },
  },
  exportJobs: {
    list: (token: string) =>
      req<ExportJobResponse[]>('/export-jobs', { method: 'GET' }, token),
  },
  drBackups: {
    list: (token: string, scope?: DrBackupScope, orgId?: string) => {
      const params = new URLSearchParams()
      if (scope) params.set('scope', scope)
      if (orgId) params.set('orgId', orgId)
      const qs = params.toString()
      return req<DrBackupResponse[]>(`/dr-backups${qs ? `?${qs}` : ''}`, { method: 'GET' }, token)
    },
    restore: (id: string, token: string) =>
      req<void>(`/dr-backups/${id}/restore`, { method: 'POST', ...payload({ confirm: true }) }, token),
  },
  billingHistory: {
    list: (orgId: string, token: string) =>
      req<BillingHistoryResponse[]>(`/orgs/${orgId}/billing-history`, { method: 'GET' }, token),
    downloadInvoice: async (orgId: string, historyId: string, token: string): Promise<Blob> => {
      const res = await fetch(`${API_BASE_URL}/orgs/${orgId}/billing-history/${historyId}/invoice`, {
        headers: { Authorization: `Bearer ${token}` },
        credentials: 'include',
      })
      if (!res.ok) {
        const text = await res.text().catch(() => '')
        throw new ApiError(res.status, res.statusText, text)
      }
      return res.blob()
    },
    updatePaymentStatus: (orgId: string, historyId: string, dto: PaymentStatusUpdateRequest, token: string) =>
      req<BillingHistoryResponse>(`/orgs/${orgId}/billing-history/${historyId}/payment-status`, { method: 'PUT', ...payload(dto) }, token),
    resendInvoice: (orgId: string, historyId: string, token: string) =>
      req<void>(`/orgs/${orgId}/billing-history/${historyId}/resend`, { method: 'POST' }, token),
  },
  pricing: {
    base: {
      list: (token: string) =>
        req<BasePricingResponse[]>('/pricing/base', { method: 'GET' }, token),
      add: (dto: BasePricingRequest, token: string) =>
        req<BasePricingResponse>('/pricing/base', { method: 'POST', ...payload(dto) }, token),
    },
    modules: {
      list: (token: string) =>
        req<ModulePricingResponse[]>('/pricing/modules', { method: 'GET' }, token),
      add: (dto: ModulePricingRequest, token: string) =>
        req<ModulePricingResponse>('/pricing/modules', { method: 'POST', ...payload(dto) }, token),
    },
    exchangeRates: {
      list: (token: string) =>
        req<ExchangeRateResponse[]>('/pricing/exchange-rates', { method: 'GET' }, token),
      add: (dto: ExchangeRateRequest, token: string) =>
        req<ExchangeRateResponse>('/pricing/exchange-rates', { method: 'POST', ...payload(dto) }, token),
    },
  },
  roles: (token: string) =>
    req<RoleResponse[]>('/roles/raum', { method: 'GET' }, token),

  services: {
    create: (dto: ServiceRequest, token: string) =>
      req<ServiceResponse>('/services', { method: 'POST', ...payload(dto) }, token),
    list: (token: string) =>
      req<ServiceResponse[]>('/services', { method: 'GET' }, token),
    get: (id: string, token: string) =>
      req<ServiceResponse>(`/services/${id}`, { method: 'GET' }, token),
    update: (id: string, dto: ServiceRequest, token: string) =>
      req<ServiceResponse>(`/services/${id}`, { method: 'PUT', ...payload(dto) }, token),
    delete: (id: string, token: string) =>
      req<void>(`/services/${id}`, { method: 'DELETE' }, token),
  },
  credentials: {
    register: (dto: Credentials, token: string) =>
      req<BasicCredential>('/credentials', { method: 'POST', ...payload(dto) }, token),
    // /credentials/ephemeral is service-to-service only and is not exposed at the
    // public gateway — there is no browser client for it.
  },
  onboarding: {
    initiate: (orgId: string, dto: OnboardingRequest, token: string) =>
      req<void>(`/onboarding/${orgId}`, { method: 'POST', ...payload(dto) }, token),
  },
}
