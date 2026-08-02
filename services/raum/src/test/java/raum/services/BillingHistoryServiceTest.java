package raum.services;

import common.exception.BadRequestException;
import common.exception.NotFoundException;
import common.mail.MailgunService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import raum.billing.InvoiceDocumentService;
import raum.models.BillingHistory;
import raum.models.Organization;
import raum.repository.BillingHistoryRepository;
import raum.repository.OrganizationRepository;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingHistoryServiceTest {

    @Mock
    private BillingHistoryRepository billingHistoryRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private InvoiceDocumentService invoiceDocumentService;
    @Mock
    private MailgunService mailgunService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID historyId = UUID.randomUUID();

    private BillingHistoryService service() {
        return new BillingHistoryService(billingHistoryRepository, organizationRepository,
                invoiceDocumentService, mailgunService);
    }

    private BillingHistory pendingEntry(Instant dueAt) {
        return BillingHistory.builder().id(historyId).orgId(orgId).billingCycle("MONTHLY")
                .dueAt(dueAt).paymentStatus("PENDING").build();
    }

    // --- updatePaymentStatus ---

    @Test
    void updatePaymentStatus_marksPaid_setsPaidAtAndReference() {
        BillingHistory entry = pendingEntry(Instant.now());
        when(billingHistoryRepository.findByIdAndOrgId(historyId, orgId)).thenReturn(Mono.just(entry));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().updatePaymentStatus(orgId, historyId, "PAID", "wire-123"))
                .assertNext(dto -> {
                    assertThat(dto.getPaymentStatus()).isEqualTo("PAID");
                    assertThat(dto.getPaymentReference()).isEqualTo("wire-123");
                    assertThat(dto.getPaidAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void updatePaymentStatus_marksPending_clearsPaidAtAndReference() {
        BillingHistory entry = BillingHistory.builder().id(historyId).orgId(orgId).billingCycle("MONTHLY")
                .dueAt(Instant.now()).paymentStatus("PAID").paidAt(Instant.now()).paymentReference("old-ref").build();
        when(billingHistoryRepository.findByIdAndOrgId(historyId, orgId)).thenReturn(Mono.just(entry));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().updatePaymentStatus(orgId, historyId, "PENDING", null))
                .assertNext(dto -> {
                    assertThat(dto.getPaymentStatus()).isEqualTo("PENDING");
                    assertThat(dto.getPaymentReference()).isNull();
                    assertThat(dto.getPaidAt()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void updatePaymentStatus_blankReference_storedAsNull() {
        BillingHistory entry = pendingEntry(Instant.now());
        when(billingHistoryRepository.findByIdAndOrgId(historyId, orgId)).thenReturn(Mono.just(entry));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().updatePaymentStatus(orgId, historyId, "PAID", "   "))
                .assertNext(dto -> assertThat(dto.getPaymentReference()).isNull())
                .verifyComplete();
    }

    @Test
    void updatePaymentStatus_invalidStatus_throwsBadRequest() {
        StepVerifier.create(service().updatePaymentStatus(orgId, historyId, "CANCELLED", null))
                .verifyError(BadRequestException.class);
    }

    @Test
    void updatePaymentStatus_nullStatus_throwsBadRequest() {
        StepVerifier.create(service().updatePaymentStatus(orgId, historyId, null, null))
                .verifyError(BadRequestException.class);
    }

    @Test
    void updatePaymentStatus_referenceOver255Chars_throwsBadRequestWithoutTouchingRepository() {
        String tooLong = "x".repeat(256);
        StepVerifier.create(service().updatePaymentStatus(orgId, historyId, "PAID", tooLong))
                .verifyError(BadRequestException.class);
        verify(billingHistoryRepository, never()).findByIdAndOrgId(any(), any());
    }

    @Test
    void updatePaymentStatus_entryNotFound_throwsNotFound() {
        when(billingHistoryRepository.findByIdAndOrgId(historyId, orgId)).thenReturn(Mono.empty());

        StepVerifier.create(service().updatePaymentStatus(orgId, historyId, "PAID", null))
                .verifyError(NotFoundException.class);
    }

    // --- resendInvoice ---

    @Test
    void resendInvoice_noBillingEmail_throwsBadRequestWithoutCallingMailgun() {
        BillingHistory entry = pendingEntry(Instant.now());
        Organization org = Organization.builder().id(orgId).name("Acme").billingEmail(null).build();
        when(billingHistoryRepository.findByIdAndOrgId(historyId, orgId)).thenReturn(Mono.just(entry));
        when(organizationRepository.findById(orgId)).thenReturn(Mono.just(org));

        StepVerifier.create(service().resendInvoice(orgId, historyId))
                .verifyError(BadRequestException.class);
        verify(mailgunService, never()).sendInvoiceEmail(any(), any(), any(), any());
    }

    @Test
    void resendInvoice_success_regeneratesPdfAndEmailsIt() {
        BillingHistory entry = pendingEntry(Instant.now());
        Organization org = Organization.builder().id(orgId).name("Acme").billingEmail("billing@acme.example").build();
        byte[] pdf = "%PDF-fake".getBytes();
        when(billingHistoryRepository.findByIdAndOrgId(historyId, orgId)).thenReturn(Mono.just(entry));
        when(organizationRepository.findById(orgId)).thenReturn(Mono.just(org));
        when(invoiceDocumentService.generate(org, entry)).thenReturn(pdf);
        when(mailgunService.sendInvoiceEmail(eq("billing@acme.example"), eq(pdf), any(), eq(null)))
                .thenReturn(Mono.empty());

        StepVerifier.create(service().resendInvoice(orgId, historyId)).verifyComplete();
        verify(mailgunService).sendInvoiceEmail(eq("billing@acme.example"), eq(pdf), any(), eq(null));
    }

    @Test
    void resendInvoice_entryNotFound_throwsNotFound() {
        when(billingHistoryRepository.findByIdAndOrgId(historyId, orgId)).thenReturn(Mono.empty());

        StepVerifier.create(service().resendInvoice(orgId, historyId))
                .verifyError(NotFoundException.class);
    }

    // --- overdue derivation (via listForOrg -> toDTO) ---

    @Test
    void listForOrg_pendingPastDue_isOverdue() {
        BillingHistory entry = pendingEntry(Instant.now().minusSeconds(3600));
        when(billingHistoryRepository.findAllByOrgIdOrderByCreatedAtDesc(orgId))
                .thenReturn(reactor.core.publisher.Flux.just(entry));

        StepVerifier.create(service().listForOrg(orgId))
                .assertNext(dto -> assertThat(dto.isOverdue()).isTrue())
                .verifyComplete();
    }

    @Test
    void listForOrg_pendingFutureDue_isNotOverdue() {
        BillingHistory entry = pendingEntry(Instant.now().plusSeconds(3600));
        when(billingHistoryRepository.findAllByOrgIdOrderByCreatedAtDesc(orgId))
                .thenReturn(reactor.core.publisher.Flux.just(entry));

        StepVerifier.create(service().listForOrg(orgId))
                .assertNext(dto -> assertThat(dto.isOverdue()).isFalse())
                .verifyComplete();
    }

    @Test
    void listForOrg_paidPastDue_isNotOverdue() {
        BillingHistory entry = BillingHistory.builder().id(historyId).orgId(orgId).billingCycle("MONTHLY")
                .dueAt(Instant.now().minusSeconds(3600)).paymentStatus("PAID").build();
        when(billingHistoryRepository.findAllByOrgIdOrderByCreatedAtDesc(orgId))
                .thenReturn(reactor.core.publisher.Flux.just(entry));

        StepVerifier.create(service().listForOrg(orgId))
                .assertNext(dto -> assertThat(dto.isOverdue()).isFalse())
                .verifyComplete();
    }
}
