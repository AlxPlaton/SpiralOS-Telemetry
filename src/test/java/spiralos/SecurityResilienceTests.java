package spiralos;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * FAILURE CATEGORY: Security - attempts to make the platform fail (or leak
 * data) through credential stuffing, cross-site request forgery, and simply
 * skipping login.
 *
 * Uses a throwaway admin password that only exists for this test run; it
 * never touches a real deployment's credentials.
 */
@SpringBootTest(properties = { "SPIRALOS_ADMIN_PASSWORD=test-only-password-123",
        "spiralos.settings.path=target/test-settings.properties" })
@AutoConfigureMockMvc
class SecurityResilienceTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Attack: hit the API with no session at all. Expect: 401, never data.")
    void unauthenticatedApiCallIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/telemetry"))
               .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/processes"))
               .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/logs"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Attack: send a state-changing request without a CSRF token. Expect: 403, change rejected.")
    void settingsChangeWithoutCsrfTokenIsRejected() throws Exception {
        String body = """
                {"refreshIntervalMs":2000,"cpuWarnPercent":70,"cpuCritPercent":85,
                 "ramWarnPercent":75,"ramCritPercent":90,"dashboardProcessRows":8,
                 "processPageRows":100,"logLines":200,
                 "idleWatts":30,"maxWatts":120,"centsPerKwh":15}""";

        mockMvc.perform(put("/api/v1/settings")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
               .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Control: the same request WITH a valid CSRF token succeeds, proving the block above is CSRF, not a bug")
    void settingsChangeWithCsrfTokenSucceeds() throws Exception {
        String body = """
                {"refreshIntervalMs":2000,"cpuWarnPercent":70,"cpuCritPercent":85,
                 "ramWarnPercent":75,"ramCritPercent":90,"dashboardProcessRows":8,
                 "processPageRows":100,"logLines":200,
                 "idleWatts":30,"maxWatts":120,"centsPerKwh":15}""";

        mockMvc.perform(put("/api/v1/settings")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Attack: 6 rapid login attempts from one address (credential stuffing). "
            + "Expect: the 6th is blocked by the lockout before credentials are even checked.")
    void bruteForceLoginIsLockedOut() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/login")
                            .with(csrf())
                            .param("username", "admin")
                            .param("password", "wrong-password-" + i))
                   .andExpect(status().is3xxRedirection());
        }

        // 6th attempt: even with the CORRECT password, the lockout must still
        // reject it, proving the throttle fires before authentication logic runs.
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "admin")
                        .param("password", "test-only-password-123"))
               .andExpect(status().is3xxRedirection())
               .andExpect(result -> {
                   String location = result.getResponse().getRedirectedUrl();
                   if (location == null || !location.contains("locked")) {
                       throw new AssertionError(
                               "Expected the 6th attempt to redirect to .../login.html?locked, got: " + location);
                   }
               });
    }

    @Test
    @DisplayName("Attack: ask for a negative/zero/huge process limit (resource exhaustion attempt). "
            + "Expect: 400, not a crash or an unbounded response.")
    void outOfRangeProcessLimitIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/processes").param("limit", "0").with(user("admin").roles("ADMIN")))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/processes").param("limit", "100000").with(user("admin").roles("ADMIN")))
               .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Attack: ask the Logs API for an arbitrary file path instead of a known source id. "
            + "Expect: 400 - clients can never read an arbitrary file off the server.")
    void arbitraryLogPathIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/logs")
                        .param("source", "/etc/shadow")
                        .with(user("admin").roles("ADMIN")))
               .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Attack: send malformed / truncated JSON to the settings API. Expect: 400, not a 500 or a stack trace.")
    void malformedJsonIsRejectedCleanly() throws Exception {
        mockMvc.perform(put("/api/v1/settings")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshIntervalMs\": 2000, \"cpuWarnPercent\": "))
               .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Attack: settings with a type-confused / overflowing value. Expect: 400, settings unchanged.")
    void typeConfusedSettingsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/settings")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshIntervalMs\":99999999999999,\"cpuWarnPercent\":\"<script>\"}"))
               .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Attack: valid JSON but a 1ms refresh interval (turn every browser into a DoS client). Expect: 400.")
    void dosRefreshIntervalRejected() throws Exception {
        mockMvc.perform(put("/api/v1/settings")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshIntervalMs":1,"cpuWarnPercent":70,"cpuCritPercent":85,
                                 "ramWarnPercent":75,"ramCritPercent":90,"dashboardProcessRows":8,
                                 "processPageRows":100,"logLines":200,
                                 "idleWatts":30,"maxWatts":120,"centsPerKwh":15}"""))
               .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Attack: path traversal in the log source id and a huge search string. Expect: 400 every time.")
    void logTraversalAndOversizedQueryRejected() throws Exception {
        mockMvc.perform(get("/api/v1/logs").param("source", "../../../../windows/win.ini")
                        .with(user("admin").roles("ADMIN")))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/logs").param("q", "A".repeat(10_000))
                        .with(user("admin").roles("ADMIN")))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/processes").param("sort", "name;DROP TABLE")
                        .with(user("admin").roles("ADMIN")))
               .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Attack: non-numeric limit to force a parsing exception. Expect: 400, no internals leaked.")
    void nonNumericParameterDoesNotLeakInternals() throws Exception {
        mockMvc.perform(get("/api/v1/processes").param("limit", "abc").with(user("admin").roles("ADMIN")))
               .andExpect(status().isBadRequest())
               .andExpect(result -> {
                   String body = result.getResponse().getContentAsString();
                   if (body.contains("Exception") || body.contains("at spiralos.")) {
                       throw new AssertionError("Response leaked server internals: " + body);
                   }
               });
    }
}
