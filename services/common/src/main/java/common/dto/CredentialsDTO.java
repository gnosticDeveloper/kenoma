package common.dto;

import lombok.*;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class CredentialsDTO extends BasicCredentialDTO {

    @NonNull
    String userName;

    @NonNull
    String password;
    String dbHost;
    Integer dbPort;
    String dbName;
    String dbEngine;
    String leaseId;
    long leaseDuration;

    /**
     * The {@link common.grants.ServiceTier} name the lease was issued at (e.g. {@code "SALES"}).
     * Set by raum on the {@code /credentials/ephemeral} response; consuming services key
     * their connection pool on it and fail closed if it disagrees with the tier they
     * resolved locally. Null when talking to a pre-tiering raum.
     */
    String tier;
}