package raum.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("billing_history")
public class BillingHistory {

    @Id
    @Column("id")
    UUID id;

    @Column("org_id")
    UUID orgId;

    @Column("billing_cycle")
    String billingCycle;

    @Column("due_at")
    Instant dueAt;

    @Column("created_at")
    Instant createdAt;

    @Column("amount")
    BigDecimal amount;

    @Column("currency")
    String currency;

    @Column("line_items")
    Json lineItems;

    @Column("payment_status")
    String paymentStatus;

    @Column("paid_at")
    Instant paidAt;

    @Column("payment_reference")
    String paymentReference;
}
