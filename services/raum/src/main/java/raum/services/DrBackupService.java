package raum.services;

import common.exception.BadRequestException;
import org.springframework.stereotype.Service;
import raum.dto.DrBackupResponseDTO;
import raum.models.DrBackup;
import raum.models.DrBackupScope;
import raum.repository.DrBackupRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
public class DrBackupService {

    private final DrBackupRepository drBackupRepository;
    private final DrRestoreService drRestoreService;

    public DrBackupService(DrBackupRepository drBackupRepository, DrRestoreService drRestoreService) {
        this.drBackupRepository = drBackupRepository;
        this.drRestoreService = drRestoreService;
    }

    /** Every DR backup catalog entry, newest first - optionally narrowed to one scope and/or org. */
    public Flux<DrBackupResponseDTO> list(String scope, UUID orgId) {
        Flux<DrBackup> backups = scope != null
                ? drBackupRepository.findAllByScopeOrderByCreatedAtDesc(scope.toUpperCase())
                : drBackupRepository.findAllByOrderByCreatedAtDesc();
        return backups
                .filter(backup -> orgId == null || orgId.equals(backup.getOrgId()))
                .map(this::toResponseDTO);
    }

    /** {@code confirm=false} refuses outright - a DR restore overwrites live data (INSTANCE scope)
     * or deletes-then-replaces an org's rows (ORG scope), so this must never fire on a bare request
     * body. */
    public Mono<Void> restore(UUID backupId, boolean confirm) {
        if (!confirm) {
            return Mono.error(new BadRequestException(
                    "Restore requires explicit confirmation - set confirm: true. This is destructive."));
        }
        return drRestoreService.restore(backupId);
    }

    private DrBackupResponseDTO toResponseDTO(DrBackup backup) {
        return DrBackupResponseDTO.builder()
                .id(backup.getId())
                .scope(backup.getScope())
                .instanceHost(backup.getInstanceHost())
                .instancePort(backup.getInstancePort())
                .instanceDb(backup.getInstanceDb())
                .orgId(backup.getOrgId())
                .serviceName(backup.getServiceName())
                .objectKey(backup.getObjectKey())
                .createdAt(backup.getCreatedAt())
                // representativeCredentialsId only means anything for INSTANCE scope - ORG-scope rows
                // never set it and are always restorable via DrRestoreService.restoreOrg.
                .restorable(!DrBackupScope.INSTANCE.name().equals(backup.getScope())
                        || backup.getRepresentativeCredentialsId() != null)
                .build();
    }
}
