package raum.services;

import common.exception.BadRequestException;
import common.exception.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import raum.dto.ExportJobResponseDTO;
import raum.models.ExportFormat;
import raum.models.ExportJob;
import raum.models.ExportJobStatus;
import raum.models.ExportLayout;
import raum.repository.ExportJobRepository;
import raum.repository.OrganizationRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ExportJobService {

    private static final List<String> ACTIVE_STATUSES =
            List.of(ExportJobStatus.PENDING.name(), ExportJobStatus.RUNNING.name());

    private final ExportJobRepository exportJobRepository;
    private final OrganizationRepository organizationRepository;

    public ExportJobService(ExportJobRepository exportJobRepository, OrganizationRepository organizationRepository) {
        this.exportJobRepository = exportJobRepository;
        this.organizationRepository = organizationRepository;
    }

    /**
     * Idempotent: if the org already has a PENDING or RUNNING export job, that job is returned
     * instead of queuing a duplicate - repeated calls (or a retry-happy client) just get the
     * in-flight job's current status rather than piling up redundant exports.
     */
    public Mono<ExportJobResponseDTO> requestExport(UUID orgId, String format, String layout) {
        // Parsing can throw (bad format/layout, or MERGED requested with SQL) - wrapped in
        // Mono.fromCallable so it surfaces as a Mono error signal (400 via the exception handler)
        // rather than throwing synchronously out of this method before a subscriber ever attaches.
        return Mono.fromCallable(() -> parseFormatAndLayout(format, layout))
                .flatMap(parsed -> organizationRepository.findById(orgId)
                        .switchIfEmpty(Mono.error(new NotFoundException("Organization not found")))
                        .flatMap(org -> exportJobRepository.findFirstByOrgIdAndStatusIn(orgId, ACTIVE_STATUSES)
                                .switchIfEmpty(Mono.defer(() -> exportJobRepository.save(ExportJob.builder()
                                                .orgId(orgId)
                                                .status(ExportJobStatus.PENDING.name())
                                                .format(parsed.format().name())
                                                .layout(parsed.layout().name())
                                                .requestedAt(Instant.now())
                                                .build())
                                        // The check above is not atomic with this insert, so a concurrent
                                        // request can win the race and insert its own active job first. The
                                        // partial unique index on (org_id) WHERE status IN (...) catches that
                                        // at the database level; fall back to returning whichever job won
                                        // instead of surfacing the constraint violation to the caller.
                                        .onErrorResume(DataIntegrityViolationException.class, e ->
                                                exportJobRepository.findFirstByOrgIdAndStatusIn(orgId, ACTIVE_STATUSES)
                                                        .switchIfEmpty(Mono.error(e))))))
                        .map(this::toResponseDTO));
    }

    /** Every export job across every org, newest first - for the platform operator's exports overview.
     * Access is restricted to ORG_MANAGE at the security-config/routing layer, not here. */
    public Flux<ExportJobResponseDTO> listAll() {
        return exportJobRepository.findAllByOrderByRequestedAtDesc().map(this::toResponseDTO);
    }

    public Mono<ExportJobResponseDTO> getJob(UUID orgId, UUID jobId) {
        return exportJobRepository.findByIdAndOrgId(jobId, orgId)
                .switchIfEmpty(Mono.error(new NotFoundException("Export job not found")))
                .map(this::toResponseDTO);
    }

    /** The object keys uploaded for a DONE job, ready to be presigned. Errors 400 if the job hasn't
     * finished yet (nothing to download), 404 if the job doesn't exist for this org. */
    public Mono<List<String>> getDownloadKeys(UUID orgId, UUID jobId) {
        return exportJobRepository.findByIdAndOrgId(jobId, orgId)
                .switchIfEmpty(Mono.error(new NotFoundException("Export job not found")))
                .flatMap(job -> {
                    if (!ExportJobStatus.DONE.name().equals(job.getStatus())) {
                        return Mono.error(new BadRequestException("Export job is not done yet (status: " + job.getStatus() + ")"));
                    }
                    List<String> keys = job.getObjectKeys() == null || job.getObjectKeys().isBlank()
                            ? List.of() : List.of(job.getObjectKeys().split(","));
                    return Mono.just(keys);
                });
    }

    private FormatAndLayout parseFormatAndLayout(String format, String layout) {
        ExportFormat parsedFormat = parseFormat(format);
        ExportLayout parsedLayout = parseLayout(layout);
        if (parsedFormat == ExportFormat.SQL && parsedLayout == ExportLayout.MERGED) {
            throw new BadRequestException("layout=MERGED is not supported for the SQL format - " +
                    "SQL always produces one restorable dump per service");
        }
        return new FormatAndLayout(parsedFormat, parsedLayout);
    }

    private ExportFormat parseFormat(String format) {
        if (format == null) {
            return ExportFormat.SQL;
        }
        try {
            return ExportFormat.valueOf(format.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown export format: " + format + ". Must be SQL, JSON or CSV");
        }
    }

    private ExportLayout parseLayout(String layout) {
        if (layout == null) {
            return ExportLayout.SEPARATE;
        }
        try {
            return ExportLayout.valueOf(layout.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown export layout: " + layout + ". Must be SEPARATE or MERGED");
        }
    }

    private record FormatAndLayout(ExportFormat format, ExportLayout layout) {}

    private ExportJobResponseDTO toResponseDTO(ExportJob job) {
        return ExportJobResponseDTO.builder()
                .id(job.getId())
                .orgId(job.getOrgId())
                .status(job.getStatus())
                .format(job.getFormat())
                .layout(job.getLayout())
                .requestedAt(job.getRequestedAt())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .errorMessage(job.getErrorMessage())
                .build();
    }
}
