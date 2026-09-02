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

export type MovementType = 'INBOUND' | 'OUTBOUND' | 'ADJUSTMENT' | 'TRANSFER_OUT' | 'TRANSFER_IN' | 'SALE'

export type MovementStatus = 'PENDING' | 'POSTED' | 'CANCELLED'

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
  cost?: number
  costCurrency?: string
  baseUom?: string
  uomConversions?: UomConversionRequest[]
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
  // Purchase cost (COGS), independent of price - not affected by ?currency conversion
  cost?: number | null
  costCurrency?: string | null
  // The unit stock is tracked in for this variant (e.g. "units", "kg")
  baseUom: string
  // Alternate units this variant can be bought/sold in, and their conversion factor to baseUom
  uomConversions: UomConversionResponse[]
  // Barcodes linked to this variant (provider-supplied or system-issued); at most one is primary
  barcodes: VariantBarcodeResponse[]
}

export type BarcodeSymbology = 'EAN13' | 'UPC_A' | 'EAN8' | 'CODE128' | 'CODE39'
export type BarcodeSource = 'PROVIDER' | 'ISSUED'

export interface VariantBarcodeRequest {
  barcode: string
  symbology: BarcodeSymbology
  // The variant's base unit, or one of its configured pack sizes (e.g. "case"). Omit for base unit.
  uom?: string
  isPrimary?: boolean
}

export interface VariantBarcodeIssueRequest {
  uom?: string
  isPrimary?: boolean
}

export interface VariantBarcodePrimaryRequest {
  isPrimary: boolean
}

export interface VariantBarcodeResponse {
  id: string
  orgId: string
  variantId: string
  barcode: string
  symbology: BarcodeSymbology
  source: BarcodeSource
  // The unit this barcode identifies (base unit, or a pack size)
  uom: string
  // Base units per scan: 1 for the base unit, or the pack size's factor; null if that conversion was removed
  factor: number | null
  isPrimary: boolean
  createdAt: string
}

export interface BarcodeLookupResponse {
  barcode: string
  symbology: BarcodeSymbology
  productId: string
  productSku: string
  productName: string
  // Unit this barcode identifies, and how many base units one scan represents
  uom: string
  factor: number | null
  // Price for one scan of this barcode (unit price for a base-unit barcode, pack price otherwise)
  packPrice: number | null
  variant: ProductVariantResponse
  // Batch/lot carried by a GS1-128 scan, resolved against the variant's batches. Null on a plain scan
  batchCode: string | null
  batchExpiry: string | null
  // ACTIVE | RECALLED for a known lot; UNKNOWN when a lot was scanned but not on file; null otherwise
  batchStatus: string | null
  expired: boolean
  recalled: boolean
}

export interface OrgBarcodeSettingsRequest {
  // Digits only, 4-11 long; null/empty clears it and falls back to the restricted-distribution range
  gs1Prefix?: string | null
}

export interface OrgBarcodeSettingsResponse {
  orgId: string
  gs1Prefix: string | null
  nextSequence: number
  createdAt: string | null
  modifiedAt: string | null
}

export interface OrgUnitRequest {
  name: string
}

export interface OrgUnitResponse {
  id: string
  orgId: string
  name: string
  // Built-in standard metric unit (kg, g, m, cm, l, ml) or the generic count unit (units), with
  // automatic conversions between them - as opposed to a custom org-defined unit (e.g. "case")
  standard: boolean
  createdAt: string
}

export interface UomConversionRequest {
  uomName: string
  factor: number
  // Optional flat price for one of this unit (bulk discount); falls back to factor * variant price
  price?: number
}

export interface UomConversionResponse {
  id: string
  orgId: string
  variantId: string
  uomName: string
  factor: number
  // Explicit override, if set; null when falling back to factor * variant price
  price: number | null
  // The explicit price above if set, otherwise factor * the variant's price; null if the variant has no price
  effectivePrice: number | null
  createdAt: string
  modifiedAt: string
}

export interface VariantPriceUpdate {
  variantId: string
  price: number
}

export interface VariantBatchPriceRequest {
  items: VariantPriceUpdate[]
}

export interface VariantCostUpdate {
  variantId: string
  cost: number
}

export interface VariantBatchCostRequest {
  items: VariantCostUpdate[]
}

export interface ProductRequest {
  sku: string
  name: string
  description?: string
  isActive?: boolean
  // Track this product's stock by production batch (lot) and expiry date
  tracksBatches?: boolean
}

export interface ProductResponse {
  id: string
  orgId: string
  sku: string
  name: string
  description: string | null
  isActive: boolean
  tracksBatches: boolean
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
  // Optional unit the delta above is expressed in (e.g. "case"), if different from the
  // variant's base unit. Must be a unit configured via the uom-conversions endpoints
  uom?: string
  // POSTED (default) applies the delta immediately; PENDING records it without touching stock
  status?: MovementStatus
  referenceId?: string
  note?: string
  // Batch-tracked products only. Inbound: name the batch (batchId, or batchCode + optional expiryDate,
  // or a raw gs1 scan). Outbound: an explicit batchId to draw from, or omit for first-expired-first-out
  batchId?: string
  batchCode?: string
  expiryDate?: string
  gs1?: string
}

export interface StockMovementResponse {
  id: string
  orgId: string
  productId: string
  variantId: string
  locationId: string
  movementType: MovementType
  status: MovementStatus
  // Always in the variant's base unit
  delta: number
  uom: string | null
  uomQuantity: number | null
  referenceId: string | null
  note: string | null
  createdAt: string
  createdBy: string
  batchId: string | null
  // Set only on the aggregate result of a FEFO outbound split across several batches
  allocations: StockMovementResponse[] | null
}

export interface StockBalanceResponse {
  orgId: string
  variantId: string
  locationId: string
  quantity: number
  modifiedAt: string
}

// ── Batches (lots) & recalls ──

export type BatchStatus = 'ACTIVE' | 'RECALLED'

export interface BatchLocationBalance {
  locationId: string
  locationName: string
  quantity: number
}

export interface BatchResponse {
  id: string
  variantId: string
  batchCode: string
  expiryDate: string | null
  status: BatchStatus
  recalledAt: string | null
  recallNote: string | null
  createdAt: string
  balances: BatchLocationBalance[]
  totalQuantity: number
}

export interface RecallReport {
  batch: BatchResponse
  affectedLocations: BatchLocationBalance[]
  history: StockMovementResponse[]
}

export interface RecallRequest {
  note?: string
}

export interface OrgBatchSettingsRequest {
  nearExpiryDays: number
}

export interface OrgBatchSettingsResponse {
  orgId: string
  nearExpiryDays: number
  createdAt: string | null
  modifiedAt: string | null
}

// ── Transfer orders ──

export type TransferStatus =
  | 'DRAFT'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'IN_TRANSIT'
  | 'PARTIALLY_RECEIVED'
  | 'COMPLETED'
  | 'CANCELLED'

export interface StockTransferLineRequest {
  variantId: string
  quantity: number
  uom?: string
}

export interface StockTransferRequest {
  reference?: string
  note?: string
  sourceLocationId: string
  destLocationId: string
  lines: StockTransferLineRequest[]
}

export interface StockTransferLineBatch {
  batchId: string
  batchCode: string
  expiryDate: string | null
  status: string
  qtyDispatched: number
  qtyReceived: number
  qtyInTransit: number
}

export interface StockTransferLineResponse {
  id: string
  variantId: string
  sourceLocationId: string
  destLocationId: string
  qtyRequested: number
  qtyDispatched: number
  qtyReceived: number
  qtyInTransit: number
  uom: string | null
  uomQuantity: number | null
  batches: StockTransferLineBatch[]
}

export interface StockTransferResponse {
  id: string
  orgId: string
  reference: string | null
  status: TransferStatus
  note: string | null
  sourceLocationId: string | null
  destLocationId: string | null
  lines: StockTransferLineResponse[]
  createdAt: string
  createdBy: string | null
  submittedAt: string | null
  submittedBy: string | null
  approvedAt: string | null
  approvedBy: string | null
  dispatchedAt: string | null
  dispatchedBy: string | null
  completedAt: string | null
  completedBy: string | null
  cancelledAt: string | null
  cancelledBy: string | null
}

export interface StockTransferReceiveLine {
  lineId: string
  qtyReceived: number
  uom?: string
}

export interface StockTransferReceiveRequest {
  lines: StockTransferReceiveLine[]
  closeShort: boolean
}

export interface InTransitStock {
  variantId: string
  destLocationId: string
  quantity: number
}

// ── Sales (point of sale) ──

export type SaleStatus = 'COMPLETED' | 'VOIDED'

export interface SaleLineRequest {
  // Identify the variant by one of these. A barcode also fixes the unit sold
  barcode?: string
  variantId?: string
  quantity: number
  // Unit the quantity is in (ignored when barcode is given). Omit for the base unit
  uom?: string
  // Till-side price override for one unit sold. Omit to use the variant's effective price
  unitPrice?: number
}

export interface SaleRequest {
  locationId: string
  reference?: string
  note?: string
  lines: SaleLineRequest[]
}

export interface SaleLineResponse {
  id: string
  variantId: string
  barcode: string | null
  // Quantity sold, normalized to the variant's base unit
  qtyBase: number
  uom: string | null
  uomQuantity: number | null
  unitPrice: number
  lineTotal: number
}

export interface SaleResponse {
  id: string
  orgId: string
  locationId: string
  reference: string | null
  status: SaleStatus
  subtotal: number
  currency: string | null
  note: string | null
  lines: SaleLineResponse[]
  soldAt: string
  soldBy: string | null
  voidedAt: string | null
  voidedBy: string | null
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
