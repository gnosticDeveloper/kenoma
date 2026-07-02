import type {
  LocationRequest,
  LocationResponse,
  MetadataOptionPatch,
  MetadataOptionRequest,
  MetadataOptionResponse,
  ProductMetadataAssignmentItem,
  ProductMetadataRequest,
  ProductMetadataResponse,
  ProductRequest,
  ProductResponse,
  ProductVariantRequest,
  ProductVariantResponse,
  StockBalanceResponse,
  StockMovementRequest,
  StockMovementResponse,
} from '../types'

async function req<T>(path: string, init: RequestInit, token: string): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`

  const res = await fetch(path, { ...init, headers })

  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(`${res.status} ${res.statusText}${text ? ': ' + text : ''}`)
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

function payload(data: unknown): Pick<RequestInit, 'body'> {
  return { body: JSON.stringify(data) }
}

function query(params: Record<string, string | undefined>): string {
  const entries = Object.entries(params).filter(([, v]) => v)
  if (entries.length === 0) return ''
  return '?' + new URLSearchParams(entries as [string, string][]).toString()
}

export const bime = {
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
    list: (token: string) =>
      req<ProductResponse[]>('/products', { method: 'GET' }, token),
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
    list: (productId: string, token: string) =>
      req<ProductVariantResponse[]>(`/products/${productId}/variants`, { method: 'GET' }, token),
    get: (productId: string, variantId: string, token: string) =>
      req<ProductVariantResponse>(`/products/${productId}/variants/${variantId}`, { method: 'GET' }, token),
    patch: (productId: string, variantId: string, dto: ProductVariantRequest, token: string) =>
      req<ProductVariantResponse>(`/products/${productId}/variants/${variantId}`, { method: 'PATCH', ...payload(dto) }, token),
    deactivate: (productId: string, variantId: string, token: string) =>
      req<void>(`/products/${productId}/variants/${variantId}`, { method: 'DELETE' }, token),
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
  },
}
