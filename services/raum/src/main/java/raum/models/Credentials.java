package raum.models;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("credentials")
public class Credentials {
    @Id
    @Column("id")
    private UUID id;

    @Column("org_id")
    private UUID orgId;

    @Column("service_id")
    private UUID serviceId;

    @Column("db_engine")
    private String dbEngine;

    @Column("db_host")
    private String dbHost;

    @Column("db_port")
    private Integer dbPort;

    @Column("db_name")
    private String dbName;

    @Column("modification_lock")
    private boolean modificationLock;

    @Column("locked_at")
    private Instant lockedAt;

    @Column("created_at")
    private Instant createdAt;

    @Column("modified_at")
    private Instant modifiedAt;
}