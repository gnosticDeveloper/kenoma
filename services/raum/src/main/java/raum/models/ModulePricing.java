package raum.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
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
@Table("module_pricing")
public class ModulePricing {

    @Id
    @Column("id")
    UUID id;

    @Column("service_id")
    UUID serviceId;

    @Column("price")
    BigDecimal price;

    @Column("currency")
    String currency;

    @Column("included_in_base")
    boolean includedInBase;

    @Column("effective_from")
    Instant effectiveFrom;

    @Column("created_at")
    Instant createdAt;
}
