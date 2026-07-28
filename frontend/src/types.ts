export interface LoginRequest {
  orgId: string
  username: string
  password: string
}

export interface OrgRequest {
  name: string
  contactEmail: string
  contactName: string
}

export interface OrgResponse {
  id: string
  name: string
  contactEmail: string
  taxId?: string | null
  fiscalName?: string | null
  fiscalAddress?: string | null
  billingEmail?: string | null
  billingEmailVerified?: boolean
  billingCycle?: string | null
  nextInvoiceDueAt?: string | null
  currency?: string | null
}

export type BillingCycle = 'MONTHLY' | 'QUARTERLY' | 'ANNUAL'

export interface BillingInfoRequest {
  taxId?: string
  fiscalName?: string
  fiscalAddress?: string
  billingCycle?: BillingCycle | ''
  nextInvoiceDueAt?: string
  currency?: string
}

export interface BillingEmailRequest {
  billingEmail: string
  locale?: string
}

export interface BillingHistoryResponse {
  id: string
  orgId: string
  billingCycle: string
  dueAt: string
  createdAt: string
  amount: number | null
  currency: string | null
  lineItems: string | null
}

export interface BasePricingRequest {
  price: number
  currency: string
  effectiveFrom?: string
}

export interface BasePricingResponse {
  id: string
  price: number
  currency: string
  effectiveFrom: string
  createdAt: string
}

export interface ModulePricingRequest {
  serviceId: string
  price: number
  currency: string
  includedInBase: boolean
  effectiveFrom?: string
}

export interface ModulePricingResponse {
  id: string
  serviceId: string
  serviceName: string | null
  price: number
  currency: string
  includedInBase: boolean
  effectiveFrom: string
  createdAt: string
}

export interface ExchangeRateRequest {
  fromCurrency: string
  toCurrency: string
  rate: number
  effectiveFrom?: string
}

export interface ExchangeRateResponse {
  id: string
  fromCurrency: string
  toCurrency: string
  rate: number
  effectiveFrom: string
  createdAt: string
}

export interface ServiceRequest {
  name: string
  description: string
}

export interface ServiceResponse {
  id: string
  name: string
  description: string
}

export interface BasicCredential {
  orgId: string
  serviceId: string
}

export interface Credentials extends BasicCredential {
  userName: string
  password: string
  dbHost: string
  dbPort: number
  dbName: string
  dbEngine: string
  leaseId?: string
  leaseDuration?: number
}

export type BimePreset = 'CLOTHING_STORE' | 'BOOK_STORE' | 'REPAIR_SHOP' | 'STORAGE_WAREHOUSE'

export interface OnboardingRequest {
  name: string
  lastName: string
  email: string
  username: string
  bimePreset: BimePreset
  locale?: string
}

export interface UserRequest {
  email: string
  name: string
  lastName: string
  username: string
  roles: Record<string, string[]>
  locale?: string
}

export interface UserResponse {
  id: string
  name: string
  lastName: string
  email: string
  username: string
  roles: Record<string, string[]>
}

// ── Bime ──

export interface LocationRequest {
  name: string
  code: string
  isActive?: boolean
}

export interface LocationResponse {
  id: string
  orgId: string
  name: string
  code: string
  isActive: boolean
  createdAt: string
  modifiedAt: string
}

export interface MetadataOptionRequest {
  value: string
}

export interface MetadataOptionResponse {
  id: string
  metadataId: string
  value: string
  createdAt: string
}

export interface MetadataOptionPatch {
  add: string[]
  remove: string[]
}

export interface ProductMetadataRequest {
  name: string
}

export interface ProductMetadataResponse {
  id: string
  orgId: string
  name: string
  options: MetadataOptionResponse[]
  createdAt: string
}

export interface ProductMetadataAssignmentItem {
  metadataId: string
  optionIds: string[]
}

export interface AssignedMetadata {
  metadataId: string
  metadataName: string
  selectedOptions: MetadataOptionResponse[]
}

export type MovementType = 'INBOUND' | 'OUTBOUND' | 'ADJUSTMENT'

export interface VariantStock {
  locationId: string
  quantity: number
  modifiedAt: string
}

export interface ProductVariantRequest {
  optionIds: string[]
  sku?: string
  isActive?: boolean
}

export interface ProductVariantResponse {
  id: string
  productId: string
  orgId: string
  sku: string | null
  isActive: boolean
  createdAt: string
  options: MetadataOptionResponse[]
  stock: VariantStock[]
}

export interface ProductRequest {
  sku: string
  name: string
  description?: string
  isActive?: boolean
}

export interface ProductResponse {
  id: string
  orgId: string
  sku: string
  name: string
  description: string | null
  isActive: boolean
  createdAt: string
  modifiedAt: string
  // Populated by GET /products/{id}; omitted (null) by the GET /products list endpoint
  metadata: AssignedMetadata[] | null
  variants: ProductVariantResponse[] | null
}

export interface StockMovementRequest {
  variantId: string
  locationId: string
  movementType: MovementType
  delta: number
  referenceId?: string
  note?: string
}

export interface StockMovementResponse {
  id: string
  orgId: string
  productId: string
  variantId: string
  locationId: string
  movementType: MovementType
  delta: number
  referenceId: string | null
  note: string | null
  createdAt: string
  createdBy: string
}

export interface StockBalanceResponse {
  orgId: string
  variantId: string
  locationId: string
  quantity: number
  modifiedAt: string
}
