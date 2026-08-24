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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("export_jobs")
public class ExportJob {

    @Id
    @Column("id")
    UUID id;

    @Column("org_id")
    UUID orgId;

    @Column("status")
    String status;

    @Column("format")
    String format;

    @Column("layout")
    String layout;

    @Column("requested_at")
    Instant requestedAt;

    @Column("started_at")
    Instant startedAt;

    @Column("completed_at")
    Instant completedAt;

    @Column("error_message")
    String errorMessage;

    /** Comma-separated object keys uploaded to the bucket for this job - one per service under
     * SEPARATE layout, one under MERGED. Null/empty until the job reaches DONE. */
    @Column("object_keys")
    String objectKeys;
}
