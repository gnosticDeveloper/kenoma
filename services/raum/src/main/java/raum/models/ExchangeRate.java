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
@Table("exchange_rates")
public class ExchangeRate {

    @Id
    @Column("id")
    UUID id;

    @Column("from_currency")
    String fromCurrency;

    @Column("to_currency")
    String toCurrency;

    @Column("rate")
    BigDecimal rate;

    @Column("effective_from")
    Instant effectiveFrom;

    @Column("created_at")
    Instant createdAt;
}
