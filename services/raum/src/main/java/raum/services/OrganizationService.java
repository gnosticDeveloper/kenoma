package raum.services;

import common.exception.BadRequestException;
import common.exception.ConflictException;
import common.exception.NotFoundException;
import common.mail.MailgunService;
import common.security.VerificationTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import raum.dto.BillingEmailRequestDTO;
import raum.dto.BillingEmailVerifyRequestDTO;
import raum.dto.BillingInfoRequestDTO;
import raum.dto.OrgRequestDTO;
import raum.dto.OrgResponseDTO;
import raum.models.BillingCycle;
import raum.models.CurrencyRefreshCadence;
import raum.models.CurrencyRefreshMode;
import raum.models.Organization;
import raum.models.PendingOrgVerification;
import raum.repository.OrganizationRepository;
import raum.repository.PendingOrgVerificationRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Slf4j
@Service
public class OrganizationService {

    private static final String BILLING_EMAIL_FIELD = "BILLING_EMAIL";
    private static final String CONTACT_EMAIL_FIELD = "CONTACT_EMAIL";

    final OrganizationRepository repository;
    private final PendingOrgVerificationRepository pendingOrgVerificationRepository;
    private final VerificationTokenService verificationTokenService;
    private final MailgunService mailgunService;

    public OrganizationService(OrganizationRepository repository,
                                PendingOrgVerificationRepository pendingOrgVerificationRepository,
                                VerificationTokenService verificationTokenService,
                                MailgunService mailgunService) {
        this.repository = repository;
        this.pendingOrgVerificationRepository = pendingOrgVerificationRepository;
        this.verificationTokenService = verificationTokenService;
        this.mailgunService = mailgunService;
    }

    public Mono<OrgResponseDTO> registerOrg(OrgRequestDTO dto) {
        Mono<OrgResponseDTO> blank = requireNonBlank(dto.getName(), "name");
        if (blank == null) blank = requireNonBlank(dto.getContactName(), "contactName");
        if (blank == null) blank = requireNonBlank(dto.getContactEmail(), "contactEmail");
        if (blank != null) {
            return blank;
        }

        Organization org = Organization.builder()
                .contactName(dto.getContactName())
                .contactEmail(dto.getContactEmail())
                .contactEmailVerified(false)
                .name(dto.getName())
                .build();

        // The org name's uniqueness is enforced by a DB-level UNIQUE constraint (the only thing
        // that's actually race-proof against two concurrent registrations of the same name) -
        // translate the resulting constraint violation into a clean 409 instead of letting a raw
        // DB error surface as an unhandled 500.
        return repository.save(org)
                .flatMap(saved -> sendContactEmailVerification(saved).thenReturn(saved))
                .map(this::toResponseDTO)
                .onErrorMap(DataIntegrityViolationException.class, e ->
                        new ConflictException("An organization named '" + dto.getName() + "' already exists"));
    }

    public Flux<OrgResponseDTO> getAllOrgs() {
        return repository.findAll()
                .filter(organization -> organization.getStoppedAt() == null)
                .map(this::toResponseDTO);
    }

    /** For machine-to-machine callers (e.g. Bime's stock alert scheduler) that only need org IDs to iterate over. */
    public Flux<UUID> getActiveOrgIds() {
        return repository.findAllByStoppedAtIsNull().map(Organization::getId);
    }

    public Mono<OrgResponseDTO> getOrgDataById(UUID id) {
        return repository.findById(id).map(this::toResponseDTO);
    }

    public Mono<OrgResponseDTO> updateOrg(UUID id, OrgRequestDTO dto) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NotFoundException("Organization not found")))
                .flatMap(org -> {
                    boolean emailChanged = dto.getContactEmail() != null && !dto.getContactEmail().equals(org.getContactEmail());
                    org.setName(dto.getName());
                    org.setContactName(dto.getContactName());
                    org.setContactEmail(dto.getContactEmail());
                    org.setModifiedAt(Instant.now());
                    if (emailChanged) {
                        org.setContactEmailVerified(false);
                    }
                    Mono<Organization> saved = repository.save(org);
                    if (!emailChanged) {
                        return saved;
                    }
                    return saved.flatMap(s -> sendContactEmailVerification(s).thenReturn(s));
                })
                .map(this::toResponseDTO);
    }

    // Best-effort: registering/updating an org should succeed even if the verification email
    // fails to send (e.g. Mailgun hiccup) - unlike billing-email verification, this isn't an
    // explicit user action the whole request exists to perform.
    private Mono<Void> sendContactEmailVerification(Organization org) {
        return createPendingVerification(org.getId(), CONTACT_EMAIL_FIELD, org.getContactEmail())
                .flatMap(token -> mailgunService.sendContactEmailVerification(org.getContactEmail(), org.getId(), token, null))
                .onErrorResume(e -> {
                    log.warn("Failed to send contact email verification for org {}", org.getId(), e);
                    return Mono.empty();
                });
    }

    public Mono<Void> deleteOrg(UUID id) {
        return repository.findById(id)
                .flatMap(org -> {
                    org.setStoppedAt(Instant.now());
                    return repository.save(org);
                })
                .switchIfEmpty(Mono.error(new NotFoundException("Organization not found")))
                .then();
    }

    public Mono<OrgResponseDTO> updateBillingInfo(UUID orgId, BillingInfoRequestDTO dto) {
        Mono<OrgResponseDTO> blank = requireNonBlank(dto.getBillingCycle(), "billingCycle");
        if (blank != null) {
            return blank;
        }
        try {
            BillingCycle.valueOf(dto.getBillingCycle());
        } catch (IllegalArgumentException e) {
            return Mono.error(new BadRequestException("Unknown billing cycle: " + dto.getBillingCycle()));
        }
        if (dto.getCurrencyRefreshMode() != null) {
            try {
                CurrencyRefreshMode.valueOf(dto.getCurrencyRefreshMode().toUpperCase());
            } catch (IllegalArgumentException e) {
                return Mono.error(new BadRequestException("Unknown currencyRefreshMode: " + dto.getCurrencyRefreshMode()));
            }
        }
        final CurrencyRefreshCadence cadence;
        if (dto.getCurrencyRefreshCadence() != null) {
            try {
                cadence = CurrencyRefreshCadence.valueOf(dto.getCurrencyRefreshCadence().toUpperCase());
            } catch (IllegalArgumentException e) {
                return Mono.error(new BadRequestException("Unknown currencyRefreshCadence: " + dto.getCurrencyRefreshCadence()));
            }
            if (cadence == CurrencyRefreshCadence.EVERY_N_DAYS
                    && (dto.getCurrencyRefreshIntervalDays() == null
                        || dto.getCurrencyRefreshIntervalDays() < 1
                        || dto.getCurrencyRefreshIntervalDays() > 365)) {
                return Mono.error(new BadRequestException(
                        "currencyRefreshIntervalDays must be between 1 and 365 when currencyRefreshCadence is EVERY_N_DAYS"));
            }
        } else {
            cadence = null;
        }

        return repository.findById(orgId)
                .switchIfEmpty(Mono.error(new NotFoundException("Organization not found")))
                .flatMap(org -> {
                    org.setTaxId(dto.getTaxId());
                    org.setFiscalName(dto.getFiscalName());
                    org.setFiscalAddress(dto.getFiscalAddress());
                    org.setBillingCycle(dto.getBillingCycle());
                    org.setNextInvoiceDueAt(dto.getNextInvoiceDueAt());
                    org.setCurrency(dto.getCurrency() != null ? dto.getCurrency().toUpperCase() : null);
                    if (dto.getCurrencyRefreshMode() != null) {
                        org.setCurrencyRefreshMode(dto.getCurrencyRefreshMode().toUpperCase());
                    }
                    if (cadence != null) {
                        org.setCurrencyRefreshCadence(cadence.name());
                        org.setCurrencyRefreshIntervalDays(
                                cadence == CurrencyRefreshCadence.EVERY_N_DAYS ? dto.getCurrencyRefreshIntervalDays() : null);
                    }
                    if (dto.getProductPricingCurrency() != null) {
                        org.setProductPricingCurrency(dto.getProductPricingCurrency().toUpperCase());
                    }
                    org.setModifiedAt(Instant.now());
                    return repository.save(org);
                })
                .map(this::toResponseDTO);
    }

    public Mono<Void> requestBillingEmailVerification(UUID orgId, BillingEmailRequestDTO dto) {
        Mono<Void> blank = requireNonBlank(dto.getBillingEmail(), "billingEmail");
        if (blank != null) {
            return blank;
        }
        return repository.findById(orgId)
                .switchIfEmpty(Mono.error(new NotFoundException("Organization not found")))
                .flatMap(org -> {
                    org.setBillingEmail(dto.getBillingEmail());
                    org.setBillingEmailVerified(false);
                    org.setModifiedAt(Instant.now());

                    return repository.save(org)
                            .then(createPendingVerification(orgId, BILLING_EMAIL_FIELD, dto.getBillingEmail()))
                            .flatMap(token -> mailgunService.sendBillingEmailVerification(
                                    dto.getBillingEmail(), orgId, token, dto.getLocale()));
                });
    }

    public Mono<Void> confirmBillingEmail(UUID orgId, BillingEmailVerifyRequestDTO dto) {
        Mono<Void> blank = requireNonBlank(dto.getToken(), "token");
        if (blank != null) {
            return blank;
        }
        return confirmEmailVerification(orgId, dto.getToken(), BILLING_EMAIL_FIELD);
    }

    public Mono<Void> confirmContactEmail(UUID orgId, BillingEmailVerifyRequestDTO dto) {
        Mono<Void> blank = requireNonBlank(dto.getToken(), "token");
        if (blank != null) {
            return blank;
        }
        return confirmEmailVerification(orgId, dto.getToken(), CONTACT_EMAIL_FIELD);
    }

    private Mono<String> createPendingVerification(UUID orgId, String fieldName, String email) {
        String token = verificationTokenService.generateToken();
        String tokenHash = verificationTokenService.hashToken(token);
        PendingOrgVerification verification = PendingOrgVerification.builder()
                .orgId(orgId)
                .fieldName(fieldName)
                .email(email)
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();
        return pendingOrgVerificationRepository.save(verification).thenReturn(token);
    }

    private Mono<Void> confirmEmailVerification(UUID orgId, String token, String fieldName) {
        String tokenHash = verificationTokenService.hashToken(token);
        return pendingOrgVerificationRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(tokenHash, Instant.now())
                .switchIfEmpty(Mono.error(new NotFoundException("Invalid or expired verification token")))
                .flatMap(verification -> {
                    if (!verification.getOrgId().equals(orgId) || !verification.getFieldName().equals(fieldName)) {
                        return Mono.error(new NotFoundException("Invalid or expired verification token"));
                    }
                    return repository.findById(orgId)
                            .switchIfEmpty(Mono.error(new NotFoundException("Organization not found")))
                            .flatMap(org -> {
                                String currentEmail = fieldName.equals(BILLING_EMAIL_FIELD)
                                        ? org.getBillingEmail() : org.getContactEmail();
                                if (!verification.getEmail().equals(currentEmail)) {
                                    return Mono.error(new NotFoundException("Invalid or expired verification token"));
                                }
                                if (fieldName.equals(BILLING_EMAIL_FIELD)) {
                                    org.setBillingEmailVerified(true);
                                } else {
                                    org.setContactEmailVerified(true);
                                }
                                org.setModifiedAt(Instant.now());
                                verification.setUsed(true);
                                return repository.save(org)
                                        .then(pendingOrgVerificationRepository.save(verification));
                            });
                })
                .then();
    }

    private static <T> Mono<T> requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return Mono.error(new BadRequestException(fieldName + " is required"));
        }
        return null;
    }

    private OrgResponseDTO toResponseDTO(Organization organization) {
        return OrgResponseDTO.builder()
                .id(organization.getId())
                .name(organization.getName())
                .contactEmail(organization.getContactEmail())
                .contactEmailVerified(organization.isContactEmailVerified())
                .taxId(organization.getTaxId())
                .fiscalName(organization.getFiscalName())
                .fiscalAddress(organization.getFiscalAddress())
                .billingEmail(organization.getBillingEmail())
                .billingEmailVerified(organization.isBillingEmailVerified())
                .billingCycle(organization.getBillingCycle())
                .nextInvoiceDueAt(organization.getNextInvoiceDueAt())
                .currency(organization.getCurrency())
                .currencyRefreshMode(organization.getCurrencyRefreshMode())
                .currencyRefreshCadence(organization.getCurrencyRefreshCadence())
                .currencyRefreshIntervalDays(organization.getCurrencyRefreshIntervalDays())
                .productPricingCurrency(organization.getProductPricingCurrency())
                .build();
    }
}
