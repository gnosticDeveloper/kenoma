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
}