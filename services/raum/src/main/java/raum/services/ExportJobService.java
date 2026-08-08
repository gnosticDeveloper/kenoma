package raum.services;

import common.exception.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import raum.dto.ExportJobResponseDTO;
import raum.models.ExportJob;
import raum.models.ExportJobStatus;
import raum.repository.ExportJobRepository;
import raum.repository.OrganizationRepository;
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
    public Mono<ExportJobResponseDTO> requestExport(UUID orgId) {
        return organizationRepository.findById(orgId)
                .switchIfEmpty(Mono.error(new NotFoundException("Organization not found")))
                .flatMap(org -> exportJobRepository.findFirstByOrgIdAndStatusIn(orgId, ACTIVE_STATUSES)
                        .switchIfEmpty(Mono.defer(() -> exportJobRepository.save(ExportJob.builder()
                                        .orgId(orgId)
                                        .status(ExportJobStatus.PENDING.name())
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
                .map(this::toResponseDTO);
    }

    public Mono<ExportJobResponseDTO> getJob(UUID orgId, UUID jobId) {
        return exportJobRepository.findByIdAndOrgId(jobId, orgId)
                .switchIfEmpty(Mono.error(new NotFoundException("Export job not found")))
                .map(this::toResponseDTO);
    }

    private ExportJobResponseDTO toResponseDTO(ExportJob job) {
        return ExportJobResponseDTO.builder()
                .id(job.getId())
                .orgId(job.getOrgId())
                .status(job.getStatus())
                .requestedAt(job.getRequestedAt())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .errorMessage(job.getErrorMessage())
                .build();
    }
}
