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
@Table("services")
public class Service {
    @Id
    @Column("id")
    UUID id;
    @Column("name")
    String name;
    @Column("description")
    String description;
    @Column("created_at")
    Instant createdAt;
    @Column("modified_at")
    Instant modifiedAt;
    @Column("stopped_at")
    Instant stoppedAt;
}