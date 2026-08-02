package raum.services;

import common.exception.BadRequestException;
import common.exception.NotFoundException;
import common.mail.MailgunService;
import org.springframework.stereotype.Service;
import raum.billing.InvoiceDocumentService;
import raum.dto.BillingHistoryResponseDTO;
import raum.models.BillingHistory;
import raum.models.Organization;
import raum.models.PaymentStatus;
import raum.repository.BillingHistoryRepository;
import raum.repository.OrganizationRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Service
public class BillingHistoryService {

    private final BillingHistoryRepository billingHistoryRepository;
    private final OrganizationRepository organizationRepository;
    private final InvoiceDocumentService invoiceDocumentService;
    private final MailgunService mailgunService;

    public BillingHistoryService(BillingHistoryRepository billingHistoryRepository,
                                  OrganizationRepository organizationRepository,
                                  InvoiceDocumentService invoiceDocumentService,
                                  MailgunService mailgunService) {
        this.billingHistoryRepository = billingHistoryRepository;
        this.organizationRepository = organizationRepository;
        this.invoiceDocumentService = invoiceDocumentService;
        this.mailgunService = mailgunService;
    }

    public Flux<BillingHistoryResponseDTO> listForOrg(UUID orgId) {
        return billingHistoryRepository.findAllByOrgIdOrderByCreatedAtDesc(orgId).map(this::toDTO);
    }

    public Mono<byte[]> getInvoicePdf(UUID orgId, UUID historyId) {
        return findEntryAndOrg(orgId, historyId)
                .map(pair -> invoiceDocumentService.generate(pair.org(), pair.entry()));
    }

    public Mono<BillingHistoryResponseDTO> updatePaymentStatus(UUID orgId, UUID historyId, String status, String reference) {
        PaymentStatus parsed;
        try {
            parsed = PaymentStatus.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Mono.error(new BadRequestException("Invalid payment status: " + status));
        }
        String trimmedReference = reference != null && !reference.isBlank() ? reference.trim() : null;
        if (trimmedReference != null && trimmedReference.length() > 255) {
            return Mono.error(new BadRequestException("Payment reference must be 255 characters or fewer"));
        }
        return billingHistoryRepository.findByIdAndOrgId(historyId, orgId)
                .switchIfEmpty(Mono.error(new NotFoundException("Billing history entry not found")))
                .flatMap(entry -> {
                    entry.setPaymentStatus(parsed.name());
                    if (parsed == PaymentStatus.PAID) {
                        entry.setPaidAt(Instant.now());
                        entry.setPaymentReference(trimmedReference);
                    } else {
                        entry.setPaidAt(null);
                        entry.setPaymentReference(null);
                    }
                    return billingHistoryRepository.save(entry);
                })
                .map(this::toDTO);
    }

    public Mono<Void> resendInvoice(UUID orgId, UUID historyId) {
        return findEntryAndOrg(orgId, historyId)
                .flatMap(pair -> {
                    if (pair.org().getBillingEmail() == null) {
                        return Mono.error(new BadRequestException("Organization has no billing email configured"));
                    }
                    byte[] pdf = invoiceDocumentService.generate(pair.org(), pair.entry());
                    String fileName = "invoice-%s.pdf".formatted(pair.entry().getId());
                    return mailgunService.sendInvoiceEmail(pair.org().getBillingEmail(), pdf, fileName, null);
                });
    }

    private Mono<EntryAndOrg> findEntryAndOrg(UUID orgId, UUID historyId) {
        return billingHistoryRepository.findByIdAndOrgId(historyId, orgId)
                .switchIfEmpty(Mono.error(new NotFoundException("Billing history entry not found")))
                .flatMap(entry -> organizationRepository.findById(orgId)
                        .switchIfEmpty(Mono.error(new NotFoundException("Organization not found")))
                        .map(org -> new EntryAndOrg(entry, org)));
    }

    private BillingHistoryResponseDTO toDTO(BillingHistory entity) {
        boolean overdue = PaymentStatus.PENDING.name().equals(entity.getPaymentStatus())
                && entity.getDueAt() != null
                && entity.getDueAt().isBefore(Instant.now());
        return BillingHistoryResponseDTO.builder()
                .id(entity.getId())
                .orgId(entity.getOrgId())
                .billingCycle(entity.getBillingCycle())
                .dueAt(entity.getDueAt())
                .createdAt(entity.getCreatedAt())
                .amount(entity.getAmount())
                .currency(entity.getCurrency())
                .lineItems(entity.getLineItems() != null ? entity.getLineItems().asString() : null)
                .paymentStatus(entity.getPaymentStatus())
                .overdue(overdue)
                .paidAt(entity.getPaidAt())
                .paymentReference(entity.getPaymentReference())
                .build();
    }

    private record EntryAndOrg(BillingHistory entry, Organization org) {
    }
}
