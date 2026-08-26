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
  contactEmailVerified?: boolean
  taxId?: string | null
  fiscalName?: string | null
  fiscalAddress?: string | null
  billingEmail?: string | null
  billingEmailVerified?: boolean
  billingCycle?: string | null
  nextInvoiceDueAt?: string | null
  currency?: string | null
  currencyRefreshMode?: string | null
  currencyRefreshCadence?: string | null
  currencyRefreshIntervalDays?: number | null
  // Currency the org prices its own catalog/inventory in - independent of `currency` above,
  // which is what Kenoma invoices the org's own subscription in.
  productPricingCurrency?: string | null
}

export type BillingCycle = 'MONTHLY' | 'QUARTERLY' | 'ANNUAL'

export type CurrencyRefreshMode = 'MANUAL' | 'PERIODIC'

export type CurrencyRefreshCadence = 'DAILY' | 'WEEKLY' | 'EVERY_N_DAYS' | 'MONTHLY'

export interface BillingInfoRequest {
  taxId?: string
  fiscalName?: string
  fiscalAddress?: string
  billingCycle?: BillingCycle | ''
  nextInvoiceDueAt?: string
  currency?: string
  currencyRefreshMode?: CurrencyRefreshMode | ''
  currencyRefreshCadence?: CurrencyRefreshCadence | ''
  currencyRefreshIntervalDays?: number
  productPricingCurrency?: string
}

export interface BillingEmailRequest {
  billingEmail: string
  locale?: string
}

export interface BillingEmailVerifyRequest {
  token: string
}

export type PaymentStatus = 'PENDING' | 'PAID'

export interface BillingHistoryResponse {
  id: string
  orgId: string
  billingCycle: string
  dueAt: string
  createdAt: string
  amount: number | null
  currency: string | null
  lineItems: string | null
  paymentStatus: PaymentStatus
  overdue: boolean
  paidAt: string | null
  paymentReference: string | null
}

export interface PaymentStatusUpdateRequest {
  status: PaymentStatus
  reference?: string
}

export type ExportFormat = 'SQL' | 'JSON' | 'CSV'
export type ExportLayout = 'SEPARATE' | 'MERGED'
export type ExportJobStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED'

export interface ExportJobResponse {
  id: string
  orgId: string
  status: ExportJobStatus
  format: ExportFormat
  layout: ExportLayout
  requestedAt: string
  startedAt: string | null
  completedAt: string | null
  errorMessage: string | null
}

export interface ExportFilePart {
  key: string
  index: number
}

export interface ExportDownloadResponse {
  jobId: string
  files: ExportFilePart[]
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

export interface RoleResponse {
  name: string
  displayName: string
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
  notificationEmail?: string
}

export interface LocationResponse {
  id: string
  orgId: string
  name: string
  code: string
  isActive: boolean
  notificationEmail: string | null
  notificationEmailVerified?: boolean | null
  createdAt: string
  modifiedAt: string
}

export interface NotificationEmailVerifyRequest {
  orgId: string
  token: string
}

export interface StockAlertThresholdRequest {
  variantId: string
  locationId: string
  threshold: number
}

export interface StockAlertThresholdResponse {
  orgId: string
  variantId: string
  locationId: string
  threshold: number
  createdAt: string
  modifiedAt: string
}

export interface StockAlertResponse {
  orgId: string
  variantId: string
  locationId: string
  threshold: number
  quantity: number
  triggeredAt: string
}

export interface MetadataOptionRequest {
  value: string
  code?: string
}

export interface MetadataOptionResponse {
  id: string
  metadataId: string
  value: string
  code: string
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
  isActive?: boolean
  price?: number
  priceCurrency?: string
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
  // Canonical price as stored (in priceCurrency), or already converted if ?currency was requested
  price?: number | null
  priceCurrency?: string | null
}

export interface VariantPriceUpdate {
  variantId: string
  price: number
}

export interface VariantBatchPriceRequest {
  items: VariantPriceUpdate[]
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
  // Populated by GET /products (list); omitted (null) by GET /products/{id}, which reports variants instead
  variantCount: number | null
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

// ── DR Backups ──

export type DrBackupScope = 'INSTANCE' | 'ORG'

export interface DrBackupResponse {
  id: string
  scope: DrBackupScope
  instanceHost: string
  instancePort: number
  instanceDb: string
  orgId: string | null
  serviceName: string | null
  objectKey: string
  createdAt: string
  restorable: boolean
}
