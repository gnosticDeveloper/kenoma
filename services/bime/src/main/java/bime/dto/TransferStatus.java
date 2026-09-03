package bime.dto;

/**
 * Lifecycle state of a transfer order.
 *
 * <pre>
 *   DRAFT ─submit─▶ PENDING_APPROVAL ─approve─▶ APPROVED ─dispatch─▶ IN_TRANSIT
 *     │                    │                       ▲                     │
 *     │                    │  (caller holds        │                  receive
 *     │                    │   BIME_TRANSFER_APPROVE ─────────────────┐  │
 *     │                    └─reject─▶ CANCELLED                       │  ▼
 *     └─────────────── cancel ──────▶ CANCELLED           PARTIALLY_RECEIVED ─▶ COMPLETED
 * </pre>
 *
 * <p>{@code CANCELLED} is reachable from any pre-dispatch state. Once {@code IN_TRANSIT}, stock
 * has physically left the source and the transfer must be received (short receipts handle
 * anything that does not arrive). {@code COMPLETED} and {@code CANCELLED} are terminal.
 */
public enum TransferStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    IN_TRANSIT,
    PARTIALLY_RECEIVED,
    COMPLETED,
    CANCELLED
}
