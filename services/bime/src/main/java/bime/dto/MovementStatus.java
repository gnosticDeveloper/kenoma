package bime.dto;

/**
 * Lifecycle state of a single stock movement.
 *
 * <p>Only {@link #POSTED} movements are reflected in {@code variant_stock_balances}. A movement
 * created as {@link #PENDING} records intent without touching on-hand stock; it can later be
 * posted (applying its delta) or cancelled (never applying it). {@code POSTED} and
 * {@code CANCELLED} are terminal.
 */
public enum MovementStatus {
    PENDING,
    POSTED,
    CANCELLED
}
