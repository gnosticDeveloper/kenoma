package bime.dto;

/**
 * Lifecycle state of a sale. A sale is {@link #COMPLETED} on creation - it is recorded after the
 * goods and payment have changed hands. {@link #VOIDED} is reserved for a later returns/void
 * feature; validated in application code, like {@link MovementType} and transfer status.
 */
public enum SaleStatus {
    COMPLETED,
    VOIDED
}
