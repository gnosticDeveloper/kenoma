package raum;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
		"RAUM_SERVICE_ID=00000000-0000-0000-0000-000000000002",
		"VASSAGO_SERVICE_ID=00000000-0000-0000-0000-000000000003",
		"BIME_SERVICE_ID=00000000-0000-0000-0000-000000000004",
		"RAUM_OPENBAO_TOKEN=dev-root-token",
		"RAUM_JWT_TRANSIT_KEY_NAME=vassago-jwt"
})
class RaumApplicationTests {
	@Test
	void contextLoads() {
	}
}