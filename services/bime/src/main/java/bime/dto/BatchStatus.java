package bime.dto;

/**
 * Lifecycle state of a production batch (lot).
 *
 * <p>{@code ACTIVE} batches are consumed normally (FEFO or by explicit selection). A batch marked
 * {@code RECALLED} is quarantined: it is skipped by FEFO allocation and rejected for sale-type
 * OUTBOUND movements, though a disposal ADJUSTMENT may still be recorded against it. A recall can
 * be lifted, returning the batch to {@code ACTIVE}.
 */
public enum BatchStatus {
    ACTIVE,
    RECALLED
}
