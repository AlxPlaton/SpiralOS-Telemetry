package spiralos;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "SPIRALOS_ADMIN_PASSWORD=test-only-password-123")
class SpiralosTelemetryApplicationTests {

	@Test
	void contextLoads() {
	}
}