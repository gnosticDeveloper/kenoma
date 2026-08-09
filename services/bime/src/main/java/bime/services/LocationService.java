package bime.services;

import bime.db.BimeContextService;
import bime.db.BimeDbHandle;
import bime.db.BimeDbService;
import bime.dto.LocationRequestDTO;
import bime.dto.LocationResponseDTO;
import bime.openbao.OpenBaoService;
import common.exception.BadRequestException;
import common.exception.ConflictException;
import common.exception.NotFoundException;
import common.mail.MailgunService;
import common.security.VerificationTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocationService {

    private final BimeContextService ctx;
    private final BimeDbService bimeDbService;
    private final MailgunService mailgunService;
    private final VerificationTokenService verificationTokenService;
    private final OpenBaoService openBaoService;

    public Mono<LocationResponseDTO> createLocation(LocationRequestDTO dto) {
        if (dto.getCode() == null || dto.getCode().isBlank()) {
            return Mono.error(new BadRequestException("code is required"));
        }
        return ctx.withHandle((caller, handle) -> {
            DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                    INSERT INTO locations (org_id, name, code, notification_email)
                    VALUES (:orgId, :name, :code, :notificationEmail)
                    RETURNING id, org_id, name, code, is_active, notification_email, notification_email_verified, created_at, modified_at
                    """)
                    .bind("orgId", caller.getOrgId())
                    .bind("name", dto.getName())
                    .bind("code", dto.getCode());
            spec = bindNullableEmail(spec, dto.getNotificationEmail());
            return spec.fetch().one().map(this::toResponseDTO)
                    .flatMap(location -> sendNotificationEmailVerificationIfNeeded(handle, caller.getOrgId(), location))
                    .onErrorMap(DataIntegrityViolationException.class, e ->
                            new ConflictException("A location with the same code already exists"));
        });
    }

    public Mono<LocationResponseDTO> getLocationById(UUID id) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                SELECT id, org_id, name, code, is_active, notification_email, notification_email_verified, created_at, modified_at
                FROM locations
                WHERE id = :id AND org_id = :orgId
                """)
                .bind("id", id)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .one()
                .map(this::toResponseDTO)
                .switchIfEmpty(Mono.error(new NotFoundException("Location not found")))
        );
    }

    public Flux<LocationResponseDTO> getLocations() {
        return ctx.withHandleMany((caller, handle) -> handle.client().sql("""
                SELECT id, org_id, name, code, is_active, notification_email, notification_email_verified, created_at, modified_at
                FROM locations
                WHERE org_id = :orgId
                ORDER BY name
                """)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .all()
                .map(this::toResponseDTO)
        );
    }

    public Mono<LocationResponseDTO> updateLocation(UUID id, LocationRequestDTO dto) {
        if (dto.getCode() == null || dto.getCode().isBlank()) {
            return Mono.error(new BadRequestException("code is required"));
        }
        return ctx.withHandle((caller, handle) -> {
            // notification_email_verified carries over unchanged when the email itself is
            // unchanged; any actual change to the email drops it back to unverified.
            DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                    UPDATE locations
                    SET name = :name, code = :code, is_active = :isActive,
                        notification_email = :notificationEmail,
                        notification_email_verified = CASE
                            WHEN notification_email IS NOT DISTINCT FROM :notificationEmail THEN notification_email_verified
                            ELSE false
                        END,
                        modified_at = :modifiedAt
                    WHERE id = :id AND org_id = :orgId
                    RETURNING id, org_id, name, code, is_active, notification_email, notification_email_verified, created_at, modified_at
                    """)
                    .bind("name", dto.getName())
                    .bind("code", dto.getCode())
                    .bind("isActive", dto.getIsActive() != null ? dto.getIsActive() : Boolean.TRUE)
                    .bind("modifiedAt", LocalDateTime.now())
                    .bind("id", id)
                    .bind("orgId", caller.getOrgId());
            spec = bindNullableEmail(spec, dto.getNotificationEmail());
            return spec.fetch().one()
                    .map(this::toResponseDTO)
                    .switchIfEmpty(Mono.error(new NotFoundException("Location not found")))
                    .flatMap(location -> sendNotificationEmailVerificationIfNeeded(handle, caller.getOrgId(), location))
                    .onErrorMap(DataIntegrityViolationException.class, e ->
                            new ConflictException("A location with the same code already exists"));
        });
    }

    // Best-effort: creating/updating a location should succeed even if the verification email
    // fails to send (e.g. Mailgun hiccup) - the location is still created/updated either way,
    // just with notification_email_verified left false until someone confirms it (or re-saves
    // the location to retry).
    private Mono<LocationResponseDTO> sendNotificationEmailVerificationIfNeeded(
            BimeDbHandle handle, UUID orgId, LocationResponseDTO location) {
        if (location.getNotificationEmail() == null || Boolean.TRUE.equals(location.getNotificationEmailVerified())) {
            return Mono.just(location);
        }
        String token = verificationTokenService.generateToken();
        String tokenHash = verificationTokenService.hashToken(token);
        return handle.client().sql("""
                INSERT INTO pending_location_verifications (location_id, email, token_hash, expires_at)
                VALUES (:locationId, :email, :tokenHash, :expiresAt)
                """)
                .bind("locationId", location.getId())
                .bind("email", location.getNotificationEmail())
                .bind("tokenHash", tokenHash)
                .bind("expiresAt", Instant.now().plus(24, ChronoUnit.HOURS))
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> mailgunService.sendLocationNotificationEmailVerification(
                        location.getNotificationEmail(), orgId, token, null))
                .thenReturn(location)
                .onErrorResume(e -> {
                    log.warn("Failed to send notification email verification for location {}", location.getId(), e);
                    return Mono.just(location);
                });
    }

    public Mono<Void> confirmNotificationEmail(UUID orgId, String token) {
        if (token == null || token.isBlank()) {
            return Mono.error(new BadRequestException("token is required"));
        }
        String tokenHash = verificationTokenService.hashToken(token);
        return bimeDbService.getHandleViaVaultToken(orgId, openBaoService.getToken())
                .flatMap(handle -> handle.client().sql("""
                        SELECT id, location_id, email FROM pending_location_verifications
                        WHERE token_hash = :tokenHash AND used = false AND expires_at > current_timestamp
                        """)
                        .bind("tokenHash", tokenHash)
                        .fetch()
                        .one()
                        .switchIfEmpty(Mono.error(new NotFoundException("Invalid or expired verification token")))
                        .flatMap(verification -> {
                            UUID verificationId = (UUID) verification.get("id");
                            UUID locationId = (UUID) verification.get("location_id");
                            String email = (String) verification.get("email");
                            return handle.client().sql("""
                                    UPDATE locations SET notification_email_verified = true, modified_at = :modifiedAt
                                    WHERE id = :locationId AND notification_email = :email
                                    """)
                                    .bind("locationId", locationId)
                                    .bind("email", email)
                                    .bind("modifiedAt", LocalDateTime.now())
                                    .fetch()
                                    .rowsUpdated()
                                    .flatMap(rows -> rows == 0
                                            ? Mono.error(new NotFoundException("Invalid or expired verification token"))
                                            : handle.client().sql("""
                                                    UPDATE pending_location_verifications SET used = true WHERE id = :id
                                                    """)
                                                    .bind("id", verificationId)
                                                    .fetch()
                                                    .rowsUpdated());
                        }))
                .then();
    }

    private static DatabaseClient.GenericExecuteSpec bindNullableEmail(DatabaseClient.GenericExecuteSpec spec, String notificationEmail) {
        return notificationEmail != null
                ? spec.bind("notificationEmail", notificationEmail)
                : spec.bindNull("notificationEmail", String.class);
    }

    public Mono<Void> deactivateLocation(UUID id) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                UPDATE locations SET is_active = false, modified_at = :modifiedAt
                WHERE id = :id AND org_id = :orgId
                """)
                .bind("modifiedAt", LocalDateTime.now())
                .bind("id", id)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows == 0
                        ? Mono.error(new NotFoundException("Location not found"))
                        // A deactivated location shouldn't keep tripping (or holding) stock alerts —
                        // without this, the scheduler happily keeps emailing about a location that no
                        // longer exists as far as the catalog is concerned.
                        : clearStockAlertsForLocation(handle, caller.getOrgId(), id))
        ).then();
    }

    private Mono<Void> clearStockAlertsForLocation(BimeDbHandle handle, UUID orgId, UUID locationId) {
        return handle.client().sql("""
                DELETE FROM variant_stock_alerts WHERE org_id = :orgId AND location_id = :locationId
                """)
                .bind("orgId", orgId)
                .bind("locationId", locationId)
                .fetch()
                .rowsUpdated()
                .then(handle.client().sql("""
                        DELETE FROM variant_stock_alert_thresholds WHERE org_id = :orgId AND location_id = :locationId
                        """)
                        .bind("orgId", orgId)
                        .bind("locationId", locationId)
                        .fetch()
                        .rowsUpdated())
                .then();
    }

    private LocationResponseDTO toResponseDTO(Map<String, Object> row) {
        return LocationResponseDTO.builder()
                .id((UUID) row.get("id"))
                .orgId((UUID) row.get("org_id"))
                .name((String) row.get("name"))
                .code((String) row.get("code"))
                .isActive((Boolean) row.get("is_active"))
                .notificationEmail((String) row.get("notification_email"))
                .notificationEmailVerified((Boolean) row.get("notification_email_verified"))
                .createdAt((LocalDateTime) row.get("created_at"))
                .modifiedAt((LocalDateTime) row.get("modified_at"))
                .build();
    }
}
