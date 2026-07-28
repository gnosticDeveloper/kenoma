package raum.services;

import common.exception.NotFoundException;
import org.springframework.stereotype.Service;
import raum.billing.InvoiceDocumentService;
import raum.dto.BillingHistoryResponseDTO;
import raum.models.BillingHistory;
import raum.repository.BillingHistoryRepository;
import raum.repository.OrganizationRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
public class BillingHistoryService {

    private final BillingHistoryRepository billingHistoryRepository;
    private final OrganizationRepository organizationRepository;
    private final InvoiceDocumentService invoiceDocumentService;

    public BillingHistoryService(BillingHistoryRepository billingHistoryRepository,
                                  OrganizationRepository organizationRepository,
                                  InvoiceDocumentService invoiceDocumentService) {
        this.billingHistoryRepository = billingHistoryRepository;
        this.organizationRepository = organizationRepository;
        this.invoiceDocumentService = invoiceDocumentService;
    }

    public Flux<BillingHistoryResponseDTO> listForOrg(UUID orgId) {
        return billingHistoryRepository.findAllByOrgIdOrderByCreatedAtDesc(orgId).map(this::toDTO);
    }

    public Mono<byte[]> getInvoicePdf(UUID orgId, UUID historyId) {
        return billingHistoryRepository.findByIdAndOrgId(historyId, orgId)
                .switchIfEmpty(Mono.error(new NotFoundException("Billing history entry not found")))
                .flatMap(entry -> organizationRepository.findById(orgId)
                        .switchIfEmpty(Mono.error(new NotFoundException("Organization not found")))
                        .map(org -> invoiceDocumentService.generate(org, entry)));
    }

    private BillingHistoryResponseDTO toDTO(BillingHistory entity) {
        return BillingHistoryResponseDTO.builder()
                .id(entity.getId())
                .orgId(entity.getOrgId())
                .billingCycle(entity.getBillingCycle())
                .dueAt(entity.getDueAt())
                .createdAt(entity.getCreatedAt())
                .amount(entity.getAmount())
                .currency(entity.getCurrency())
                .lineItems(entity.getLineItems() != null ? entity.getLineItems().asString() : null)
                .build();
    }
}
