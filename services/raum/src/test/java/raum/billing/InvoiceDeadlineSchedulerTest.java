package raum.billing;

import common.mail.MailgunService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import raum.models.BillingHistory;
import raum.models.Organization;
import raum.repository.BillingHistoryRepository;
import raum.repository.OrganizationRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceDeadlineSchedulerTest {

    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private BillingHistoryRepository billingHistoryRepository;
    @Mock
    private PricingService pricingService;
    @Mock
    private InvoiceDocumentService invoiceDocumentService;
    @Mock
    private MailgunService mailgunService;

    private InvoiceDeadlineScheduler scheduler;

    private void stubZeroInvoice() {
        lenient().when(pricingService.calculateInvoice(any(), any(), any())).thenReturn(Mono.just(
                InvoiceCalculation.builder().amount(BigDecimal.ZERO).currency("USD").lineItems(List.of()).build()));
    }

    private Organization orgDueOneCycleBehind() {
        return Organization.builder()
                .id(UUID.randomUUID())
                .billingCycle("MONTHLY")
                .nextInvoiceDueAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .billingEmailVerified(true)
                .build();
    }

    @Test
    void checkInvoiceDeadlines_savesBillingHistoryAndAdvancesDueDate() {
        stubZeroInvoice();
        scheduler = new InvoiceDeadlineScheduler(organizationRepository, billingHistoryRepository, pricingService, invoiceDocumentService, mailgunService);
        Organization org = orgDueOneCycleBehind();
        Instant originalDueAt = org.getNextInvoiceDueAt();

        when(organizationRepository.findAllByBillingEmailVerifiedTrueAndNextInvoiceDueAtLessThanEqualAndStoppedAtIsNull(any()))
                .thenReturn(Flux.just(org));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(organizationRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        scheduler.checkInvoiceDeadlines();

        ArgumentCaptor<BillingHistory> historyCaptor = ArgumentCaptor.forClass(BillingHistory.class);
        verify(billingHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getOrgId()).isEqualTo(org.getId());
        assertThat(historyCaptor.getValue().getDueAt()).isEqualTo(originalDueAt);

        ArgumentCaptor<Organization> orgCaptor = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).save(orgCaptor.capture());
        assertThat(orgCaptor.getValue().getNextInvoiceDueAt()).isAfter(Instant.now());
    }

    @Test
    void checkInvoiceDeadlines_advancesPastMultipleMissedCycles() {
        stubZeroInvoice();
        scheduler = new InvoiceDeadlineScheduler(organizationRepository, billingHistoryRepository, pricingService, invoiceDocumentService, mailgunService);
        Organization org = Organization.builder()
                .id(UUID.randomUUID())
                .billingCycle("MONTHLY")
                .nextInvoiceDueAt(Instant.now().minus(100, ChronoUnit.DAYS))
                .billingEmailVerified(true)
                .build();

        when(organizationRepository.findAllByBillingEmailVerifiedTrueAndNextInvoiceDueAtLessThanEqualAndStoppedAtIsNull(any()))
                .thenReturn(Flux.just(org));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(organizationRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        scheduler.checkInvoiceDeadlines();

        ArgumentCaptor<Organization> orgCaptor = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).save(orgCaptor.capture());
        Instant advanced = orgCaptor.getValue().getNextInvoiceDueAt();
        assertThat(advanced).isAfter(Instant.now());
        assertThat(advanced).isBefore(Instant.now().plus(31, ChronoUnit.DAYS));
    }

    @Test
    void checkInvoiceDeadlines_isolatesFailureToOneOrg() {
        stubZeroInvoice();
        scheduler = new InvoiceDeadlineScheduler(organizationRepository, billingHistoryRepository, pricingService, invoiceDocumentService, mailgunService);
        Organization failingOrg = orgDueOneCycleBehind();
        Organization healthyOrg = orgDueOneCycleBehind();

        when(organizationRepository.findAllByBillingEmailVerifiedTrueAndNextInvoiceDueAtLessThanEqualAndStoppedAtIsNull(any()))
                .thenReturn(Flux.fromIterable(List.of(failingOrg, healthyOrg)));
        when(billingHistoryRepository.save(any()))
                .thenReturn(Mono.error(new RuntimeException("db down")))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(organizationRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        scheduler.checkInvoiceDeadlines();

        verify(organizationRepository).save(healthyOrg);
    }
}
