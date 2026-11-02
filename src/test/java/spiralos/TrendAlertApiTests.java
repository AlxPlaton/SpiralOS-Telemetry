package spiralos;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * INNOVATION (Week 10): the new history and alert endpoints get the same
 * treatment as the rest of the API: login required, every parameter bounded,
 * no internals leaked.
 */
@SpringBootTest(properties = { "SPIRALOS_ADMIN_PASSWORD=test-only-password-123",
        "spiralos.settings.path=target/test-settings.properties" })
@AutoConfigureMockMvc
class TrendAlertApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Attack: read history or alerts with no session. Expect: 401")
    void newEndpointsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/v1/telemetry/history")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/alerts")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Attack: ask for an out-of-range or non-numeric history window. Expect: 400, no stack trace")
    void historyWindowIsBounded() throws Exception {
        for (String bad : new String[] { "0", "-5", "61", "100000", "abc" }) {
            mockMvc.perform(get("/api/v1/telemetry/history").param("minutes", bad).with(user("admin").roles("ADMIN")))
                   .andExpect(status().isBadRequest())
                   .andExpect(jsonPath("$.trace").doesNotExist());
        }
    }

    @Test
    @DisplayName("Logged in: history and alerts return the expected shape")
    void endpointsReturnData() throws Exception {
        mockMvc.perform(get("/api/v1/telemetry/history").param("minutes", "5").with(user("admin").roles("ADMIN")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.minutes").value(5))
               .andExpect(jsonPath("$.points").isArray());
        mockMvc.perform(get("/api/v1/alerts").with(user("admin").roles("ADMIN")))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.active").isArray())
               .andExpect(jsonPath("$.recent").isArray());
    }
}
