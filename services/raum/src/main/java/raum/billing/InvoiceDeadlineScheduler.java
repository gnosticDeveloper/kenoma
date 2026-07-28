package raum.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.mail.MailgunService;
import io.r2dbc.postgresql.codec.Json;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import raum.models.BillingCycle;
import raum.models.BillingHistory;
import raum.models.Organization;
import raum.repository.BillingHistoryRepository;
import raum.repository.OrganizationRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneOffset;

@Slf4j
@Component
public class InvoiceDeadlineScheduler {

    private static final int MAX_CYCLE_ADVANCE_ITERATIONS = 60;

    // Not injected: this app runs on Spring Boot 4's Jackson 3 (tools.jackson) autoconfiguration,
    // which doesn't provide a com.fasterxml.jackson.databind.ObjectMapper bean.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OrganizationRepository organizationRepository;
    private final BillingHistoryRepository billingHistoryRepository;
    private final PricingService pricingService;
    private final InvoiceDocumentService invoiceDocumentService;
    private final MailgunService mailgunService;

    public InvoiceDeadlineScheduler(OrganizationRepository organizationRepository,
                                     BillingHistoryRepository billingHistoryRepository,
                                     PricingService pricingService,
                                     InvoiceDocumentService invoiceDocumentService,
                                     MailgunService mailgunService) {
        this.organizationRepository = organizationRepository;
        this.billingHistoryRepository = billingHistoryRepository;
        this.pricingService = pricingService;
        this.invoiceDocumentService = invoiceDocumentService;
        this.mailgunService = mailgunService;
    }

    @Scheduled(cron = "${raum.billing.deadline-cron:0 0 3 * * *}")
    public void checkInvoiceDeadlines() {
        Instant now = Instant.now();
        organizationRepository
                .findAllByBillingEmailVerifiedTrueAndNextInvoiceDueAtLessThanEqualAndStoppedAtIsNull(now)
                .concatMap(org -> processOrg(org, now)
                        .onErrorResume(e -> {
                            log.error("Failed to process invoice deadline for org {}", org.getId(), e);
                            return Mono.empty();
                        }))
                .then()
                .subscribe(null, e -> log.error("Invoice deadline check failed", e));
    }

    private Mono<Void> processOrg(Organization org, Instant now) {
        return pricingService.calculateInvoice(org.getId(), org.getCurrency(), now)
                .flatMap(invoice -> saveHistoryAndAdvance(org, invoice, now))
                .flatMap(entry -> sendInvoiceEmail(org, entry))
                .doOnSuccess(v -> log.info("Billing history entry created for org {}", org.getId()))
                .then();
    }

    private Mono<BillingHistory> saveHistoryAndAdvance(Organization org, InvoiceCalculation invoice, Instant now) {
        try {
            BillingHistory entry = BillingHistory.builder()
                    .orgId(org.getId())
                    .billingCycle(org.getBillingCycle())
                    .dueAt(org.getNextInvoiceDueAt())
                    .amount(invoice.getAmount())
                    .currency(invoice.getCurrency())
                    .lineItems(Json.of(OBJECT_MAPPER.writeValueAsString(invoice.getLineItems())))
                    .build();

            org.setNextInvoiceDueAt(advance(org.getNextInvoiceDueAt(), org.getBillingCycle(), now));
            org.setModifiedAt(now);

            return billingHistoryRepository.save(entry)
                    .flatMap(saved -> organizationRepository.save(org).thenReturn(saved));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    private Mono<BillingHistory> sendInvoiceEmail(Organization org, BillingHistory entry) {
        if (org.getBillingEmail() == null) {
            return Mono.just(entry);
        }
        byte[] pdf = invoiceDocumentService.generate(org, entry);
        String fileName = "invoice-%s.pdf".formatted(entry.getId());
        return mailgunService.sendInvoiceEmail(org.getBillingEmail(), pdf, fileName, null)
                .thenReturn(entry)
                .onErrorResume(e -> {
                    log.error("Failed to send invoice email for org {}", org.getId(), e);
                    return Mono.just(entry);
                });
    }

    private Instant advance(Instant dueAt, String billingCycle, Instant now) {
        BillingCycle cycle = BillingCycle.valueOf(billingCycle);
        Instant next = dueAt;
        int iterations = 0;
        while (!next.isAfter(now)) {
            if (++iterations > MAX_CYCLE_ADVANCE_ITERATIONS) {
                log.warn("Exceeded max cycle-advance iterations for due date {}, cycle {}", dueAt, billingCycle);
                break;
            }
            next = next.atZone(ZoneOffset.UTC).plus(cycle.getPeriod()).toInstant();
        }
        return next;
    }
}
