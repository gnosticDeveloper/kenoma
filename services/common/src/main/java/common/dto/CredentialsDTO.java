package common.dto;

import lombok.*;

@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@RequiredArgsConstructor
@Builder
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
}