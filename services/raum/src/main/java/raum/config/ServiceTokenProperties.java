package raum.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import raum.openbao.ServiceIdentity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps an OpenBao policy name to the {@link ServiceIdentity} a token carrying that policy
 * represents, bound from {@code raum.credentials.service-tokens.<policy-name>=<IDENTITY>}.
 *
 * <p>This is the extension point for a new service: give it an AppRole with its own
 * policy, add one line here in config, and it can authenticate to
 * {@code /credentials/ephemeral} — no raum code change.
 */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "raum.credentials")
public class ServiceTokenProperties {

    private Map<String, ServiceIdentity> serviceTokens = new LinkedHashMap<>();

}
