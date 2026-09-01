import type {
  BarcodeLookupResponse,
  BatchResponse,
  BatchStatus,
  OrgBatchSettingsRequest,
  OrgBatchSettingsResponse,
  RecallReport,
  RecallRequest,
  LocationRequest,
  LocationResponse,
  MetadataOptionPatch,
  NotificationEmailVerifyRequest,
  MetadataOptionRequest,
  MetadataOptionResponse,
  OrgBarcodeSettingsRequest,
  OrgBarcodeSettingsResponse,
  OrgUnitRequest,
  OrgUnitResponse,
  ProductMetadataAssignmentItem,
  ProductMetadataRequest,
  ProductMetadataResponse,
  ProductRequest,
  ProductResponse,
  ProductVariantRequest,
  ProductVariantResponse,
  VariantBatchCostRequest,
  VariantBatchPriceRequest,
  RoleResponse,
  StockAlertResponse,
  StockAlertThresholdRequest,
  StockAlertThresholdResponse,
  StockBalanceResponse,
  StockMovementRequest,
  StockMovementResponse,
  StockTransferReceiveRequest,
  StockTransferRequest,
  StockTransferResponse,
  InTransitStock,
  UomConversionRequest,
  UomConversionResponse,
  VariantBarcodeIssueRequest,
  VariantBarcodePrimaryRequest,
  VariantBarcodeRequest,
  VariantBarcodeResponse,
} from '../types'
import { API_BASE_URL } from './base'
import { ApiError, payload, query, req } from './client'

export interface BarcodeLabelOptions {
  which?: 'primary' | 'all'
  columns?: number
  copies?: number
  pageSize?: 'A4' | 'LETTER'
  variantId?: string
  uom?: string
}

interface StockListFilters {
  variantId?: string
  locationId?: string
  optionIds?: string[]
  matchAll?: boolean
}

interface TransferListFilters {
  status?: string
  sourceLocationId?: string
  destLocationId?: string
  variantId?: string
}

function stockQuery(filters: StockListFilters): string {
  return query({
    variantId: filters.variantId,
    locationId: filters.locationId,
    optionIds: filters.optionIds,
    matchAll: filters.matchAll ? 'true' : undefined,
  })
}

export const bime = {
  roles: (token: string) =>
    req<RoleResponse[]>('/roles/bime', { method: 'GET' }, token),

  locations: {
    create: (dto: LocationRequest, token: string) =>
      req<LocationResponse>('/locations', { method: 'POST', ...payload(dto) }, token),
    list: (token: string) =>
      req<LocationResponse[]>('/locations', { method: 'GET' }, token),
    get: (id: string, token: string) =>
      req<LocationResponse>(`/locations/${id}`, { method: 'GET' }, token),
    update: (id: string, dto: LocationRequest, token: string) =>
      req<LocationResponse>(`/locations/${id}`, { method: 'PUT', ...payload(dto) }, token),
    deactivate: (id: string, token: string) =>
      req<void>(`/locations/${id}`, { method: 'DELETE' }, token),
    confirmNotificationEmail: (dto: NotificationEmailVerifyRequest) =>
      req<void>('/locations/notification-email/confirm', { method: 'POST', ...payload(dto) }),
  },
  metadata: {
    create: (dto: ProductMetadataRequest, token: string) =>
      req<ProductMetadataResponse>('/metadata', { method: 'POST', ...payload(dto) }, token),
    list: (token: string) =>
      req<ProductMetadataResponse[]>('/metadata', { method: 'GET' }, token),
    get: (id: string, token: string) =>
      req<ProductMetadataResponse>(`/metadata/${id}`, { method: 'GET' }, token),
    delete: (id: string, token: string) =>
      req<void>(`/metadata/${id}`, { method: 'DELETE' }, token),
    addOption: (id: string, dto: MetadataOptionRequest, token: string) =>
      req<MetadataOptionResponse>(`/metadata/${id}/options`, { method: 'POST', ...payload(dto) }, token),
    removeOption: (id: string, optionId: string, token: string) =>
      req<void>(`/metadata/${id}/options/${optionId}`, { method: 'DELETE' }, token),
  },
  products: {
    create: (dto: ProductRequest, token: string) =>
      req<ProductResponse>('/products', { method: 'POST', ...payload(dto) }, token),
    list: (token: string, optionIds?: string[], matchAll?: boolean) =>
      req<ProductResponse[]>(`/products${query({ optionIds, matchAll: matchAll ? 'true' : undefined })}`, { method: 'GET' }, token),
    get: (id: string, token: string) =>
      req<ProductResponse>(`/products/${id}`, { method: 'GET' }, token),
    update: (id: string, dto: ProductRequest, token: string) =>
      req<ProductResponse>(`/products/${id}`, { method: 'PUT', ...payload(dto) }, token),
    deactivate: (id: string, token: string) =>
      req<void>(`/products/${id}`, { method: 'DELETE' }, token),
    assignMetadata: (id: string, assignments: ProductMetadataAssignmentItem[], token: string) =>
      req<void>(`/products/${id}/metadata`, { method: 'PUT', ...payload(assignments) }, token),
    patchMetadataOptions: (id: string, metadataId: string, dto: MetadataOptionPatch, token: string) =>
      req<void>(`/products/${id}/metadata/${metadataId}/options`, { method: 'PATCH', ...payload(dto) }, token),
  },
  variants: {
    create: (productId: string, dto: ProductVariantRequest, token: string) =>
      req<ProductVariantResponse>(`/products/${productId}/variants`, { method: 'POST', ...payload(dto) }, token),
    list: (productId: string, token: string, currency?: string, optionIds?: string[], matchAll?: boolean, sku?: string) =>
      req<ProductVariantResponse[]>(`/products/${productId}/variants${query({ currency, optionIds, matchAll: matchAll ? 'true' : undefined, sku })}`, { method: 'GET' }, token),
    search: (optionIds: string[] | undefined, token: string, currency?: string, matchAll?: boolean, sku?: string) =>
      req<ProductVariantResponse[]>(`/products/variants/search${query({ optionIds, currency, matchAll: matchAll ? 'true' : undefined, sku })}`, { method: 'GET' }, token),
    get: (productId: string, variantId: string, token: string, currency?: string) =>
      req<ProductVariantResponse>(`/products/${productId}/variants/${variantId}${query({ currency })}`, { method: 'GET' }, token),
    patch: (productId: string, variantId: string, dto: ProductVariantRequest, token: string) =>
      req<ProductVariantResponse>(`/products/${productId}/variants/${variantId}`, { method: 'PATCH', ...payload(dto) }, token),
    deactivate: (productId: string, variantId: string, token: string) =>
      req<void>(`/products/${productId}/variants/${variantId}`, { method: 'DELETE' }, token),
    batchUpdatePrices: (dto: VariantBatchPriceRequest, token: string) =>
      req<string[]>('/variants/pricing/batch', { method: 'PATCH', ...payload(dto) }, token),
    batchUpdateCosts: (dto: VariantBatchCostRequest, token: string) =>
      req<string[]>('/variants/pricing/cost-batch', { method: 'PATCH', ...payload(dto) }, token),
  },
  units: {
    list: (token: string) =>
      req<OrgUnitResponse[]>('/units', { method: 'GET' }, token),
    create: (dto: OrgUnitRequest, token: string) =>
      req<OrgUnitResponse>('/units', { method: 'POST', ...payload(dto) }, token),
    delete: (id: string, token: string) =>
      req<void>(`/units/${id}`, { method: 'DELETE' }, token),
  },
  uomConversions: {
    set: (variantId: string, dto: UomConversionRequest, token: string) =>
      req<UomConversionResponse>(`/variants/${variantId}/uom-conversions`, { method: 'PUT', ...payload(dto) }, token),
    list: (variantId: string, token: string) =>
      req<UomConversionResponse[]>(`/variants/${variantId}/uom-conversions`, { method: 'GET' }, token),
    delete: (variantId: string, uomName: string, token: string) =>
      req<void>(`/variants/${variantId}/uom-conversions/${encodeURIComponent(uomName)}`, { method: 'DELETE' }, token),
  },
  barcodes: {
    list: (productId: string, variantId: string, token: string) =>
      req<VariantBarcodeResponse[]>(`/products/${productId}/variants/${variantId}/barcodes`, { method: 'GET' }, token),
    link: (productId: string, variantId: string, dto: VariantBarcodeRequest, token: string) =>
      req<VariantBarcodeResponse>(`/products/${productId}/variants/${variantId}/barcodes`, { method: 'POST', ...payload(dto) }, token),
    issue: (productId: string, variantId: string, dto: VariantBarcodeIssueRequest, token: string) =>
      req<VariantBarcodeResponse>(`/products/${productId}/variants/${variantId}/barcodes/issue`, { method: 'POST', ...payload(dto) }, token),
    setPrimary: (productId: string, variantId: string, barcode: string, dto: VariantBarcodePrimaryRequest, token: string) =>
      req<VariantBarcodeResponse>(`/products/${productId}/variants/${variantId}/barcodes${query({ barcode })}`, { method: 'PATCH', ...payload(dto) }, token),
    remove: (productId: string, variantId: string, barcode: string, token: string) =>
      req<void>(`/products/${productId}/variants/${variantId}/barcodes${query({ barcode })}`, { method: 'DELETE' }, token),
    lookup: (barcode: string, token: string) =>
      req<BarcodeLookupResponse>(`/barcodes/lookup${query({ code: barcode })}`, { method: 'GET' }, token),
    getSettings: (token: string) =>
      req<OrgBarcodeSettingsResponse>('/barcodes/settings', { method: 'GET' }, token),
    updateSettings: (dto: OrgBarcodeSettingsRequest, token: string) =>
      req<OrgBarcodeSettingsResponse>('/barcodes/settings', { method: 'PUT', ...payload(dto) }, token),
    labelsPdf: async (productId: string, opts: BarcodeLabelOptions, token: string): Promise<Blob> => {
      const qs = query({
        which: opts.which,
        columns: opts.columns != null ? String(opts.columns) : undefined,
        copies: opts.copies != null ? String(opts.copies) : undefined,
        pageSize: opts.pageSize,
        variantId: opts.variantId,
        uom: opts.uom,
      })
      const res = await fetch(`${API_BASE_URL}/products/${productId}/barcode-labels${qs}`, {
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
  batches: {
    list: (
      token: string,
      filters: { variantId?: string; locationId?: string; status?: BatchStatus; expiringWithinDays?: number } = {},
    ) =>
      req<BatchResponse[]>(`/batches${query({
        variantId: filters.variantId,
        locationId: filters.locationId,
        status: filters.status,
        expiringWithinDays: filters.expiringWithinDays != null ? String(filters.expiringWithinDays) : undefined,
      })}`, { method: 'GET' }, token),
    get: (id: string, token: string) =>
      req<BatchResponse>(`/batches/${id}`, { method: 'GET' }, token),
    recallReport: (id: string, token: string) =>
      req<RecallReport>(`/batches/${id}/recall-report`, { method: 'GET' }, token),
    recall: (id: string, dto: RecallRequest, token: string) =>
      req<BatchResponse>(`/batches/${id}/recall`, { method: 'POST', ...payload(dto) }, token),
    liftRecall: (id: string, token: string) =>
      req<BatchResponse>(`/batches/${id}/lift-recall`, { method: 'POST' }, token),
    getSettings: (token: string) =>
      req<OrgBatchSettingsResponse>('/batches/settings', { method: 'GET' }, token),
    updateSettings: (dto: OrgBatchSettingsRequest, token: string) =>
      req<OrgBatchSettingsResponse>('/batches/settings', { method: 'PUT', ...payload(dto) }, token),
  },
  stock: {
    recordMovement: (dto: StockMovementRequest, token: string) =>
      req<StockMovementResponse>('/stock/movements', { method: 'POST', ...payload(dto) }, token),
    getMovement: (id: string, token: string) =>
      req<StockMovementResponse>(`/stock/movements/${id}`, { method: 'GET' }, token),
    listMovements: (token: string, filters: StockListFilters = {}) =>
      req<StockMovementResponse[]>(`/stock/movements${stockQuery(filters)}`, { method: 'GET' }, token),
    listBalances: (token: string, filters: StockListFilters = {}) =>
      req<StockBalanceResponse[]>(`/stock/balances${stockQuery(filters)}`, { method: 'GET' }, token),
    setAlertThreshold: (dto: StockAlertThresholdRequest, token: string) =>
      req<StockAlertThresholdResponse>('/stock/alerts/thresholds', { method: 'PUT', ...payload(dto) }, token),
    listAlertThresholds: (token: string, filters: StockListFilters = {}) =>
      req<StockAlertThresholdResponse[]>(`/stock/alerts/thresholds${stockQuery(filters)}`, { method: 'GET' }, token),
    deleteAlertThreshold: (variantId: string, locationId: string, token: string) =>
      req<void>(`/stock/alerts/thresholds${query({ variantId, locationId })}`, { method: 'DELETE' }, token),
    listActiveAlerts: (token: string, filters: StockListFilters = {}) =>
      req<StockAlertResponse[]>(`/stock/alerts/active${stockQuery(filters)}`, { method: 'GET' }, token),
  },
  transfers: {
    list: (token: string, filters: TransferListFilters = {}) =>
      req<StockTransferResponse[]>(`/stock/transfers${query({
        status: filters.status,
        sourceLocationId: filters.sourceLocationId,
        destLocationId: filters.destLocationId,
        variantId: filters.variantId,
      })}`, { method: 'GET' }, token),
    get: (id: string, token: string) =>
      req<StockTransferResponse>(`/stock/transfers/${id}`, { method: 'GET' }, token),
    inTransit: (token: string) =>
      req<InTransitStock[]>('/stock/transfers/in-transit', { method: 'GET' }, token),
    create: (dto: StockTransferRequest, token: string) =>
      req<StockTransferResponse>('/stock/transfers', { method: 'POST', ...payload(dto) }, token),
    update: (id: string, dto: StockTransferRequest, token: string) =>
      req<StockTransferResponse>(`/stock/transfers/${id}`, { method: 'PATCH', ...payload(dto) }, token),
    remove: (id: string, token: string) =>
      req<void>(`/stock/transfers/${id}`, { method: 'DELETE' }, token),
    submit: (id: string, token: string) =>
      req<StockTransferResponse>(`/stock/transfers/${id}/submit`, { method: 'POST' }, token),
    approve: (id: string, token: string) =>
      req<StockTransferResponse>(`/stock/transfers/${id}/approve`, { method: 'POST' }, token),
    reject: (id: string, token: string) =>
      req<StockTransferResponse>(`/stock/transfers/${id}/reject`, { method: 'POST' }, token),
    dispatch: (id: string, token: string) =>
      req<StockTransferResponse>(`/stock/transfers/${id}/dispatch`, { method: 'POST' }, token),
    cancel: (id: string, token: string) =>
      req<StockTransferResponse>(`/stock/transfers/${id}/cancel`, { method: 'POST' }, token),
    receive: (id: string, dto: StockTransferReceiveRequest, token: string) =>
      req<StockTransferResponse>(`/stock/transfers/${id}/receive`, { method: 'POST', ...payload(dto) }, token),
  },
}
