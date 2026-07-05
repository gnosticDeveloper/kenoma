package raum;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import raum.openbao.OpenBaoProvisioner;

@SpringBootTest
@TestPropertySource(properties = {
		"RAUM_SERVICE_ID=00000000-0000-0000-0000-000000000002",
		"VASSAGO_SERVICE_ID=00000000-0000-0000-0000-000000000003",
		"BIME_SERVICE_ID=00000000-0000-0000-0000-000000000004",
		"RAUM_JWT_TRANSIT_KEY_NAME=vassago-jwt"
})
class RaumApplicationTests {

	@MockitoBean
	OpenBaoProvisioner openBaoProvisioner;

	@Test
	void contextLoads() {
	}
}