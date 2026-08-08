package raum.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import raum.models.BillingHistory;
import raum.models.Organization;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceDocumentServiceTest {

    private final InvoiceDocumentService service = new InvoiceDocumentService();

    @Test
    void generate_producesNonEmptyPdf() throws Exception {
        Organization org = Organization.builder()
                .id(UUID.randomUUID())
                .name("Acme")
                .fiscalName("Acme Inc.")
                .fiscalAddress("123 Main St")
                .taxId("TAX-123")
                .build();

        ObjectMapper mapper = new ObjectMapper();
        List<InvoiceLineItem> lineItems = List.of(
                InvoiceLineItem.builder().label("Base").price(new BigDecimal("100.00")).currency("USD")
                        .includedInBase(false).build(),
                InvoiceLineItem.builder().label("Bime").price(new BigDecimal("0.00")).currency("USD")
                        .includedInBase(true).build());

        BillingHistory history = BillingHistory.builder()
                .id(UUID.randomUUID())
                .orgId(org.getId())
                .billingCycle("MONTHLY")
                .dueAt(Instant.now())
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .lineItems(Json.of(mapper.writeValueAsString(lineItems)))
                .build();

        byte[] pdf = service.generate(org, history);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }
}
