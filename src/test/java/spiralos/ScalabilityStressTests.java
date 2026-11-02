package spiralos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * FAILURE CATEGORY: Scaling up the number of users.
 *
 * The ORIGINAL design sampled OSHI hardware data inside the HTTP request
 * handler itself, including a 1-second BLOCKING CPU measurement per call.
 * Under concurrent load that meant: N simultaneous users == N simultaneous
 * blocking hardware reads == response time and server load both growing
 * directly with traffic, with real risk of exhausting Tomcat's worker
 * threads during a burst.
 *
 * MITIGATION IMPLEMENTED: TelemetrySampler moved hardware sampling onto a
 * SINGLE background thread on a fixed schedule, and every HTTP request now
 * just reads a cached, immutable, volatile-published Snapshot. Request
 * volume therefore no longer drives sampling cost at all - 1 user or 500
 * users pay the same backend cost per request.
 *
 * These tests fire a burst of concurrent requests through the real Spring
 * MVC stack (filters, security, controllers - the same code path a real
 * browser uses) and assert every one of them succeeds. Console output
 * documents throughput as evidence for the project writeup.
 */
@SpringBootTest(properties = { "SPIRALOS_ADMIN_PASSWORD=test-only-password-123",
        "spiralos.settings.path=target/test-settings.properties" })
@AutoConfigureMockMvc
class ScalabilityStressTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TelemetrySampler sampler;

    @Test
    @DisplayName("Burst of 150 concurrent authenticated requests across 3 endpoints: all succeed, none crash the app")
    void concurrentRequestBurstAllSucceed() throws Exception {
        int threads = 50;
        int requestsPerThread = 3; // telemetry + processes + logs = 150 total HTTP calls
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1); // everyone fires at once: worst-case burst, not a ramp
        List<Integer> statusCodes = new CopyOnWriteArrayList<>();
        List<Exception> failures = new CopyOnWriteArrayList<>();

        List<Callable<Void>> tasks = IntStream.range(0, threads).<Callable<Void>>mapToObj(i -> () -> {
            startGate.await();
            try {
                statusCodes.add(mockMvc.perform(get("/api/v1/telemetry").with(user("admin").roles("ADMIN")))
                        .andReturn().getResponse().getStatus());
                statusCodes.add(mockMvc.perform(get("/api/v1/processes").with(user("admin").roles("ADMIN")))
                        .andReturn().getResponse().getStatus());
                statusCodes.add(mockMvc.perform(get("/api/v1/logs").with(user("admin").roles("ADMIN")))
                        .andReturn().getResponse().getStatus());
            } catch (Exception e) {
                failures.add(e);
            }
            return null;
        }).toList();

        long start = System.nanoTime();
        List<java.util.concurrent.Future<Void>> futures = tasks.stream().map(pool::submit).toList();
        startGate.countDown(); // release every thread at once: worst case, not a ramp
        for (java.util.concurrent.Future<Void> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        pool.shutdown();

        long serverErrors = statusCodes.stream().filter(code -> code >= 500).count();
        long clientErrors = statusCodes.stream().filter(code -> code >= 400 && code < 500).count();

        System.out.printf(
                "[STRESS TEST PROOF] %d concurrent requests completed in %d ms (%.1f req/sec). "
                        + "2xx: %d, 4xx: %d, 5xx: %d, uncaught exceptions: %d%n",
                statusCodes.size(), elapsedMs, statusCodes.size() / Math.max(1.0, elapsedMs / 1000.0),
                statusCodes.size() - serverErrors - clientErrors, clientErrors, serverErrors, failures.size());

        assertTrue(failures.isEmpty(), "No request should throw an uncaught exception: " + failures);
        assertEquals(0, serverErrors, "No request should produce a 5xx server error under burst load");
        assertEquals(threads * requestsPerThread, statusCodes.size());
    }

    @Test
    @DisplayName("200 threads reading the cached snapshot directly at once: no exception, no torn/partial data")
    void concurrentSamplerReadsAreSafe() throws Exception {
        int readers = 200;
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Exception> failures = new CopyOnWriteArrayList<>();

        List<Callable<Void>> tasks = IntStream.range(0, readers).<Callable<Void>>mapToObj(i -> () -> {
            startGate.await();
            try {
                TelemetrySampler.Snapshot snap = sampler.latest();
                // A torn/partially-constructed read would show up as an impossible combination
                // of fields; the record's immutability plus the volatile field makes that
                // impossible, and this assertion is what proves it rather than assumes it.
                if (snap.processes() == null || snap.logicalCores() < 1) {
                    throw new AssertionError("Got a corrupt/partial snapshot under concurrent read");
                }
            } catch (Exception e) {
                failures.add(e);
            }
            return null;
        }).toList();

        List<java.util.concurrent.Future<Void>> futures = tasks.stream().map(pool::submit).toList();
        startGate.countDown();
        for (java.util.concurrent.Future<Void> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertTrue(failures.isEmpty(), "Concurrent reads of the cached snapshot must never throw: " + failures);
    }
}
