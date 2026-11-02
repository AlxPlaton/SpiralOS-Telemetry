package spiralos;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;

/**
 * FAILURE CATEGORY: Security / memory exhaustion - found by DistributedAttackTest and fixed.
 *
 * BEFORE the fix, every anonymous page hit created a server session (Spring Security saved the
 * requested URL in it so it could redirect back after login): 20,000 hits = 20,000 sessions held
 * for 30 minutes, without the attacker ever knowing the password.
 *
 * MITIGATION IMPLEMENTED: SecurityConfig uses a NullRequestCache. Login always lands on "/", so
 * the saved URL was never used anyway.
 *
 * Checked over REAL HTTP on purpose: spring-security-test's csrf() helper swaps the app's
 * cookie-based CSRF store for a session-based one inside shared MockMvc contexts, which would
 * create sessions production never does and make a MockMvc version of this test meaningless.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "SPIRALOS_ADMIN_PASSWORD=test-only-password-123",
        "spiralos.settings.path=target/test-settings.properties" })
class AnonymousSessionTests {

    @Autowired
    private Environment env;

    @ParameterizedTest(name = "anonymous GET {0} gets no session cookie")
    @ValueSource(strings = { "/", "/index.html", "/settings.html", "/api/v1/telemetry", "/api/v1/processes",
            "/login.html" })
    void anonymousRequestsNeverCreateASession(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<Void> r = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + env.getProperty("local.server.port") + path))
                        .GET().build(),
                HttpResponse.BodyHandlers.discarding());
        List<String> cookies = r.headers().allValues("Set-Cookie");
        assertTrue(cookies.stream().noneMatch(c -> c.startsWith("SPIRALOS_SESSION=")),
                "Anonymous " + path + " created a server session: " + cookies);
    }
}
