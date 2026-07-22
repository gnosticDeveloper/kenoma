package vassago.models;

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
@Table("users")
public class User {
    @Id
    @Column("id")
    private UUID id;

    @Column("org_id")
    private UUID orgId;

    @Column("name")
    private String name;

    @Column("last_name")
    private String lastName;

    @Column("email")
    private String email;

    @Column("username")
    private String username;

    @Column("password")
    private String password;

    @Column("roles")
    private String roles;

    @Column("modification_lock")
    private boolean modificationLock;

    @Column("locked_at")
    private Instant lockedAt;

    @Column("created_at")
    private Instant createdAt;

    @Column("modified_at")
    private Instant modifiedAt;

    @Column("stopped_at")
    private Instant stoppedAt;

    @Column("is_ready")
    private boolean isReady;

    @Column("locale")
    private String locale;
}
