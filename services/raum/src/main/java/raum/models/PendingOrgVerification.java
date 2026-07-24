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
@Table("pending_org_verifications")
public class PendingOrgVerification {

    @Id
    @Column("id")
    UUID id;

    @Column("org_id")
    UUID orgId;

    @Column("field_name")
    String fieldName;

    @Column("email")
    String email;

    @Column("token_hash")
    String tokenHash;

    @Column("expires_at")
    Instant expiresAt;

    @Column("used")
    boolean used;

    @Column("created_at")
    Instant createdAt;
}
