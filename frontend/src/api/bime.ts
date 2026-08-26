import type {
  LocationRequest,
  LocationResponse,
  MetadataOptionPatch,
  NotificationEmailVerifyRequest,
  MetadataOptionRequest,
  MetadataOptionResponse,
  ProductMetadataAssignmentItem,
  ProductMetadataRequest,
  ProductMetadataResponse,
  ProductRequest,
  ProductResponse,
  ProductVariantRequest,
  ProductVariantResponse,
  VariantBatchPriceRequest,
  RoleResponse,
  StockAlertResponse,
  StockAlertThresholdRequest,
  StockAlertThresholdResponse,
  StockBalanceResponse,
  StockMovementRequest,
  StockMovementResponse,
} from '../types'
import { payload, query, req } from './client'

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
    list: (productId: string, token: string, currency?: string, optionIds?: string[], matchAll?: boolean) =>
      req<ProductVariantResponse[]>(`/products/${productId}/variants${query({ currency, optionIds, matchAll: matchAll ? 'true' : undefined })}`, { method: 'GET' }, token),
    search: (optionIds: string[], token: string, currency?: string, matchAll?: boolean) =>
      req<ProductVariantResponse[]>(`/products/variants/search${query({ optionIds, currency, matchAll: matchAll ? 'true' : undefined })}`, { method: 'GET' }, token),
    get: (productId: string, variantId: string, token: string, currency?: string) =>
      req<ProductVariantResponse>(`/products/${productId}/variants/${variantId}${query({ currency })}`, { method: 'GET' }, token),
    patch: (productId: string, variantId: string, dto: ProductVariantRequest, token: string) =>
      req<ProductVariantResponse>(`/products/${productId}/variants/${variantId}`, { method: 'PATCH', ...payload(dto) }, token),
    deactivate: (productId: string, variantId: string, token: string) =>
      req<void>(`/products/${productId}/variants/${variantId}`, { method: 'DELETE' }, token),
    batchUpdatePrices: (dto: VariantBatchPriceRequest, token: string) =>
      req<string[]>('/variants/pricing/batch', { method: 'PATCH', ...payload(dto) }, token),
  },
  stock: {
    recordMovement: (dto: StockMovementRequest, token: string) =>
      req<StockMovementResponse>('/stock/movements', { method: 'POST', ...payload(dto) }, token),
    getMovement: (id: string, token: string) =>
      req<StockMovementResponse>(`/stock/movements/${id}`, { method: 'GET' }, token),
    listMovements: (token: string, filters: { variantId?: string; locationId?: string } = {}) =>
      req<StockMovementResponse[]>(`/stock/movements${query(filters)}`, { method: 'GET' }, token),
    listBalances: (token: string, filters: { variantId?: string; locationId?: string } = {}) =>
      req<StockBalanceResponse[]>(`/stock/balances${query(filters)}`, { method: 'GET' }, token),
    setAlertThreshold: (dto: StockAlertThresholdRequest, token: string) =>
      req<StockAlertThresholdResponse>('/stock/alerts/thresholds', { method: 'PUT', ...payload(dto) }, token),
    listAlertThresholds: (token: string, filters: { variantId?: string; locationId?: string } = {}) =>
      req<StockAlertThresholdResponse[]>(`/stock/alerts/thresholds${query(filters)}`, { method: 'GET' }, token),
    deleteAlertThreshold: (variantId: string, locationId: string, token: string) =>
      req<void>(`/stock/alerts/thresholds${query({ variantId, locationId })}`, { method: 'DELETE' }, token),
    listActiveAlerts: (token: string, filters: { variantId?: string; locationId?: string } = {}) =>
      req<StockAlertResponse[]>(`/stock/alerts/active${query(filters)}`, { method: 'GET' }, token),
  },
}
