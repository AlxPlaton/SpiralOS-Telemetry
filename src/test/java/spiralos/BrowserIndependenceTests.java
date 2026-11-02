package spiralos;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * FAILURE CATEGORY: Web browser dependency.
 *
 * Browsers disagree when a server is vague: a .js file sent without the right
 * Content-Type is executed by one browser and blocked by another, a page with
 * no cache headers can show stale telemetry in one browser only, and so on.
 *
 * MITIGATION IMPLEMENTED:
 *  - the frontend is plain ES2017 JavaScript + standard DOM/fetch: no framework,
 *    no build step, no vendor-prefixed APIs, so it behaves the same in Chrome,
 *    Firefox, Edge and Safari
 *  - every API response is JSON with an explicit Content-Type, and the client uses
 *    cache: 'no-store' so no browser serves stale numbers
 *  - the server sends explicit, standards-based security headers (nosniff, CSP,
 *    frame denial) so every browser applies the SAME rules instead of guessing
 *  - the polling loop pauses on hidden tabs (visibilitychange), avoiding
 *    browser-specific background-tab throttling from piling requests up
 *
 * These tests lock in the server half of that contract: the exact headers and
 * MIME types every browser relies on.
 */
@SpringBootTest(properties = { "SPIRALOS_ADMIN_PASSWORD=test-only-password-123",
        "spiralos.settings.path=target/test-settings.properties" })
@AutoConfigureMockMvc
class BrowserIndependenceTests {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest(name = "{0} is served as {1}")
    @CsvSource({
            "/index.html,     text/html",
            "/processes.html, text/html",
            "/logs.html,      text/html",
            "/settings.html,  text/html",
            "/common.js,      text/javascript",
            "/dashboard.js,   text/javascript",
            "/style.css,      text/css" })
    @DisplayName("Static assets carry an explicit, correct MIME type (no browser-specific sniffing)")
    void staticAssetsHaveExplicitContentType(String path, String type) throws Exception {
        mockMvc.perform(get(path).with(user("admin").roles("ADMIN")))
               .andExpect(status().isOk())
               .andExpect(header().string("Content-Type", startsWith(type)))
               .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("Login page is reachable without a session in any browser, with the same CSP")
    void loginPageIsPublicAndHardened() throws Exception {
        mockMvc.perform(get("/login.html"))
               .andExpect(status().isOk())
               .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
               .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
               .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    @DisplayName("API responses are explicit JSON and never cached by the browser")
    void apiResponsesAreJsonAndUncached() throws Exception {
        mockMvc.perform(get("/api/v1/telemetry").with(user("admin").roles("ADMIN")))
               .andExpect(status().isOk())
               .andExpect(header().string("Content-Type", startsWith("application/json")))
               .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    @DisplayName("A browser hitting a protected PAGE without a session is redirected to login, not shown an error")
    void pageWithoutSessionRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/index.html"))
               .andExpect(status().is3xxRedirection())
               .andExpect(header().string("Location", containsString("/login.html")));
    }
}
