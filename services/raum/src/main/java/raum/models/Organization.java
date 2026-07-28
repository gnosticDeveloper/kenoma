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
@Table("organizations")
public class Organization {

    @Id
    @Column("id")
    UUID id;

    @Column("name")
    String name;

    @Column("contact_name")
    String contactName;

    @Column("contact_email")
    String contactEmail;

    @Column("tax_id")
    String taxId;

    @Column("fiscal_name")
    String fiscalName;

    @Column("fiscal_address")
    String fiscalAddress;

    @Column("billing_email")
    String billingEmail;

    @Column("billing_email_verified")
    boolean billingEmailVerified;

    @Column("billing_cycle")
    String billingCycle;

    @Column("next_invoice_due_at")
    Instant nextInvoiceDueAt;

    @Column("currency")
    String currency;

    @Column("modification_lock")
    boolean modificationLock;

    @Column("locked_at")
    Instant lockedAt;

    @Column("created_at")
    Instant createdAt;

    @Column("modified_at")
    Instant modifiedAt;

    @Column("stopped_at")
    Instant stoppedAt;
}
