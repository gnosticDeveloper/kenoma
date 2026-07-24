package raum.billing;

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

    private final OrganizationRepository organizationRepository;
    private final BillingHistoryRepository billingHistoryRepository;

    public InvoiceDeadlineScheduler(OrganizationRepository organizationRepository,
                                     BillingHistoryRepository billingHistoryRepository) {
        this.organizationRepository = organizationRepository;
        this.billingHistoryRepository = billingHistoryRepository;
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
        BillingHistory entry = BillingHistory.builder()
                .orgId(org.getId())
                .billingCycle(org.getBillingCycle())
                .dueAt(org.getNextInvoiceDueAt())
                .build();

        org.setNextInvoiceDueAt(advance(org.getNextInvoiceDueAt(), org.getBillingCycle(), now));
        org.setModifiedAt(now);

        return billingHistoryRepository.save(entry)
                .then(organizationRepository.save(org))
                .doOnSuccess(v -> log.info("Billing history entry created for org {}", org.getId()))
                .then();
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
