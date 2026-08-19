package raum.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/** One catalog entry for a DR backup artifact actually uploaded to the bucket - either an
 * instance-level pg_dump ({@link DrBackupScope#INSTANCE}) or a per-org/per-service rewind dump
 * ({@link DrBackupScope#ORG}). Restore reads this instead of re-deriving target coordinates or
 * listing S3 directly. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("dr_backups")
public class DrBackup {

    @Id
    @Column("id")
    UUID id;

    @Column("scope")
    String scope;

    /** INSTANCE scope only - where the dump was taken from and must be replayed into. */
    @Column("instance_host")
    String instanceHost;

    @Column("instance_port")
    Integer instancePort;

    @Column("instance_db")
    String instanceDb;

    /** INSTANCE scope only - the {@code Credentials} row used to reach this instance at backup
     * time, kept so restore can fetch admin creds via {@code getStaticCredentials} without
     * re-running instance discovery. */
    @Column("representative_credentials_id")
    UUID representativeCredentialsId;

    /** ORG scope only. */
    @Column("org_id")
    UUID orgId;

    @Column("service_name")
    String serviceName;

    @Column("object_key")
    String objectKey;

    @Column("created_at")
    Instant createdAt;
}
