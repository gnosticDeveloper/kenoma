package raum.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

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
}
