package raum.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Raum API")
                        .version("0.0.1-SNAPSHOT")
                        .description("Services, Credentials, and Organizations"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT issued by Vassago. Required for all endpoints except POST /credentials/ephemeral"))
                        .addSecuritySchemes("vaultToken", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Vault-Token")
                                .description("OpenBao AppRole token. Accepted as an alternative to a JWT on POST /credentials/ephemeral for service-to-service callers")));
    }
}
