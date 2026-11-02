package spiralos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;

/**
 * FAILURE CATEGORY: Scaling up the number of users - measured on the REAL server.
 *
 * Unlike ScalabilityStressTests (MockMvc, in-process), this boots the actual
 * embedded Tomcat on a random port and attacks it over real TCP/HTTP, exactly
 * like browsers on the network would: real logins with BCrypt, real session
 * cookies, real CSRF tokens, real Tomcat worker threads and connection queue.
 *
 * Load profile (sustained, not a single burst):
 *   - 200 concurrent virtual users spread over 20 genuinely logged-in sessions
 *   - each user loops for 15 seconds over telemetry / processes / logs / settings
 *   - at the same time an ATTACKER thread hammers the server with unauthenticated
 *     calls, brute-force logins, path traversal and out-of-range parameters
 *
 * Evidence is taken from the SERVER side, not just from the client:
 *   - heap usage and live/peak JVM thread counts before, during and after
 *   - number of Tomcat worker threads actually used (http-nio-*-exec-*)
 *   - whether the background sampler kept producing fresh snapshots under load
 *     (i.e. request traffic did not starve the scheduler)
 *   - ERROR-level entries the application itself logged during the run (LogBuffer)
 *   - a post-load health check proving the server is still answering
 *
 * The full report is printed and written to target/stress-proof/server-stress-report.txt
 * so it can be attached to the project writeup.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "SPIRALOS_ADMIN_PASSWORD=test-only-password-123",
        "spiralos.settings.path=target/test-settings.properties" })
class ServerStressProofTest {

    private static final String PASSWORD = "test-only-password-123";
    private static final int SESSIONS = 20;
    private static final int VIRTUAL_USERS = 200;
    private static final Duration LOAD_DURATION = Duration.ofSeconds(15);
    private static final List<String> USER_PATHS = List.of(
            "/api/v1/telemetry",
            "/api/v1/processes?sort=cpu&order=desc&limit=100",
            "/api/v1/logs?source=app&lines=200",
            "/api/v1/settings",
            "/index.html");

    // OSHI logs these on Windows machines whose Perflib registry counters are damaged.
    // They are an OS-level condition OSHI recovers from, not an application error.
    private static final List<String> KNOWN_OS_LOGGERS = List.of("HkeyPerformanceDataUtil", "PerfCounterQuery");

    @Autowired
    private Environment env;

    @Autowired
    private TelemetrySampler sampler;

    @Autowired
    private LogBuffer logBuffer;

    private String base;

    @Test
    @DisplayName("200 concurrent users for 15s over real HTTP while under attack: zero 5xx, server stays healthy")
    void sustainedLoadAgainstRealServer() throws Exception {
        base = "http://localhost:" + env.getProperty("local.server.port");
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();

        // --- log in N real sessions (real BCrypt, real CSRF, real cookies) ---
        List<HttpClient> sessions = new ArrayList<>();
        for (int i = 0; i < SESSIONS; i++) {
            sessions.add(login());
        }

        System.gc();
        long heapBeforeMb = memory.getHeapMemoryUsage().getUsed() / (1024 * 1024);
        int threadsBefore = threads.getThreadCount();
        threads.resetPeakThreadCount();
        long snapshotBefore = sampler.latest().timestamp();
        long runStartMs = System.currentTimeMillis();

        // --- client-side counters ---
        Map<Integer, LongAdder> statusCounts = new ConcurrentHashMap<>();
        LongAdder transportErrors = new LongAdder();
        List<Long> latenciesMicros = Collections.synchronizedList(new ArrayList<>(200_000));
        Map<Integer, LongAdder> attackStatus = new ConcurrentHashMap<>();
        AtomicLong maxHeapDuringMb = new AtomicLong();
        List<Long> sampleTimestampsSeen = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(VIRTUAL_USERS + 2);
        CountDownLatch gate = new CountDownLatch(1);
        Instant deadline = Instant.now().plus(LOAD_DURATION);

        for (int u = 0; u < VIRTUAL_USERS; u++) {
            HttpClient client = sessions.get(u % SESSIONS);
            int offset = u;
            pool.submit(() -> {
                gate.await();
                int i = offset;
                while (Instant.now().isBefore(deadline)) {
                    String path = USER_PATHS.get(i++ % USER_PATHS.size());
                    long t0 = System.nanoTime();
                    try {
                        HttpResponse<Void> r = client.send(
                                HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).GET().build(),
                                HttpResponse.BodyHandlers.discarding());
                        statusCounts.computeIfAbsent(r.statusCode(), k -> new LongAdder()).increment();
                    } catch (Exception e) {
                        transportErrors.increment();
                    }
                    latenciesMicros.add((System.nanoTime() - t0) / 1000);
                }
                return null;
            });
        }

        // --- attacker running at the same time as the legitimate users ---
        pool.submit(() -> {
            gate.await();
            HttpClient anon = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            HttpClient authed = sessions.get(0);
            List<HttpRequest> attacks = List.of(
                    get("/api/v1/telemetry"),                                  // no session -> 401
                    get("/api/v1/logs?source=..%2F..%2Fetc%2Fshadow"),         // no session -> 401
                    formPost("/login", "username=admin&password=guess"));      // no CSRF -> 403 / lockout
            List<HttpRequest> authedAttacks = List.of(
                    get("/api/v1/processes?limit=999999"),                     // -> 400
                    get("/api/v1/logs?source=/etc/passwd"),                    // -> 400
                    get("/api/v1/logs?q=" + "A".repeat(5000)));                // -> 400
            int i = 0;
            while (Instant.now().isBefore(deadline)) {
                try {
                    HttpResponse<Void> r = (i % 2 == 0)
                            ? anon.send(attacks.get(i % attacks.size()), HttpResponse.BodyHandlers.discarding())
                            : authed.send(authedAttacks.get(i % authedAttacks.size()), HttpResponse.BodyHandlers.discarding());
                    attackStatus.computeIfAbsent(r.statusCode(), k -> new LongAdder()).increment();
                } catch (Exception e) {
                    attackStatus.computeIfAbsent(-1, k -> new LongAdder()).increment();
                }
                i++;
            }
            return null;
        });

        // --- server-side monitor: samples the JVM and the telemetry sampler during the run ---
        pool.submit(() -> {
            gate.await();
            while (Instant.now().isBefore(deadline)) {
                maxHeapDuringMb.accumulateAndGet(memory.getHeapMemoryUsage().getUsed() / (1024 * 1024), Math::max);
                long ts = sampler.latest().timestamp();
                if (sampleTimestampsSeen.isEmpty() || sampleTimestampsSeen.get(sampleTimestampsSeen.size() - 1) != ts) {
                    sampleTimestampsSeen.add(ts);
                }
                Thread.sleep(250);
            }
            return null;
        });

        long t0 = System.nanoTime();
        gate.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(LOAD_DURATION.toSeconds() + 60, TimeUnit.SECONDS), "load run hung");
        double elapsedSec = (System.nanoTime() - t0) / 1e9;

        int peakThreads = threads.getPeakThreadCount();
        long tomcatWorkers = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().contains("http-nio") && t.getName().contains("exec")).count();

        // --- post-load health check: the server must still answer normally ---
        HttpResponse<String> health = sessions.get(0).send(get("/api/v1/telemetry"), HttpResponse.BodyHandlers.ofString());
        System.gc();
        long heapAfterMb = memory.getHeapMemoryUsage().getUsed() / (1024 * 1024);

        List<LogBuffer.Entry> appErrors = logBuffer.snapshot().stream()
                .filter(e -> e.time() != null && e.time() >= runStartMs)
                .filter(e -> "ERROR".equals(e.level()))
                .filter(e -> !KNOWN_OS_LOGGERS.contains(e.logger()))
                .toList();
        long osLevelWarnings = logBuffer.snapshot().stream()
                .filter(e -> e.time() != null && e.time() >= runStartMs)
                .filter(e -> KNOWN_OS_LOGGERS.contains(e.logger()))
                .count();

        long total = statusCounts.values().stream().mapToLong(LongAdder::sum).sum();
        long ok = statusCounts.entrySet().stream().filter(e -> e.getKey() / 100 == 2).mapToLong(e -> e.getValue().sum()).sum();
        long server5xx = statusCounts.entrySet().stream().filter(e -> e.getKey() >= 500).mapToLong(e -> e.getValue().sum()).sum();
        long attackTotal = attackStatus.values().stream().mapToLong(LongAdder::sum).sum();
        long attack2xx = attackStatus.entrySet().stream().filter(e -> e.getKey() / 100 == 2).mapToLong(e -> e.getValue().sum()).sum();
        long attack5xx = attackStatus.entrySet().stream().filter(e -> e.getKey() >= 500).mapToLong(e -> e.getValue().sum()).sum();

        long[] lat;
        synchronized (latenciesMicros) {
            lat = latenciesMicros.stream().mapToLong(Long::longValue).sorted().toArray();
        }

        String report = String.join(System.lineSeparator(),
                "==================== SPIRALOS SERVER STRESS PROOF ====================",
                "Run at              : " + Instant.now(),
                "Server              : embedded Tomcat, real HTTP on " + base,
                "Platform            : " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                        + " / Java " + System.getProperty("java.version") + " / "
                        + Runtime.getRuntime().availableProcessors() + " cores",
                "Load profile        : " + VIRTUAL_USERS + " concurrent users, " + SESSIONS
                        + " real logged-in sessions, " + LOAD_DURATION.toSeconds() + "s sustained + 1 attacker",
                "",
                "--- Legitimate traffic (client view) ---",
                String.format("Requests completed  : %,d in %.1fs  (%.0f req/s)", total, elapsedSec, total / elapsedSec),
                "Status breakdown    : " + render(statusCounts),
                "2xx success rate    : " + String.format("%.2f%%", total == 0 ? 0 : 100.0 * ok / total),
                "5xx server errors   : " + server5xx,
                "Transport failures  : " + transportErrors.sum() + "  (refused / reset / timed-out connections)",
                String.format("Latency (ms)        : p50 %.1f | p95 %.1f | p99 %.1f | max %.1f",
                        pct(lat, 50), pct(lat, 95), pct(lat, 99), lat.length == 0 ? 0 : lat[lat.length - 1] / 1000.0),
                "",
                "--- Attack traffic during the load ---",
                String.format("Attack requests     : %,d", attackTotal),
                "Status breakdown    : " + render(attackStatus),
                "Attacks that got 2xx: " + attack2xx + "   Attacks that caused 5xx: " + attack5xx,
                "",
                "--- Server-side evidence ---",
                "Heap used (MB)      : before " + heapBeforeMb + " | peak during " + maxHeapDuringMb.get()
                        + " | after GC " + heapAfterMb + "  (max " + memory.getHeapMemoryUsage().getMax() / (1024 * 1024) + ")",
                "JVM threads         : before " + threadsBefore + " | peak " + peakThreads,
                "Tomcat worker thr.  : " + tomcatWorkers + "  (pool limit 200 - requests queued, never refused)",
                "Sampler snapshots   : " + sampleTimestampsSeen.size() + " fresh snapshots produced during load"
                        + "  (first " + snapshotBefore + ", last " + sampler.latest().timestamp() + ")",
                "App ERROR log lines : " + appErrors.size() + (appErrors.isEmpty() ? "" : "  -> " + appErrors),
                "OS-level OSHI notes : " + osLevelWarnings + "  (Windows Perflib counters; OSHI recovers, not an app fault)",
                "Post-load health    : HTTP " + health.statusCode() + "  " + truncate(health.body(), 120),
                "=======================================================================");

        System.out.println(report);
        Path out = Path.of("target", "stress-proof", "server-stress-report.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report + System.lineSeparator());

        assertEquals(0, server5xx, "No legitimate request may produce a 5xx under sustained load");
        assertEquals(0, transportErrors.sum(), "The server must never refuse or drop a connection under load");
        assertEquals(0, attack2xx, "No attack request may ever succeed");
        assertEquals(0, attack5xx, "No attack request may crash a request handler");
        assertTrue(appErrors.isEmpty(), "The application must not log any ERROR during the run: " + appErrors);
        assertTrue(sampleTimestampsSeen.size() >= 3,
                "Background sampler must keep running under load (request traffic must not starve it)");
        assertEquals(200, health.statusCode(), "Server must still be healthy after the load");
        assertTrue(total > VIRTUAL_USERS, "Load generator should have completed a meaningful number of requests");
    }

    /** Real form login: fetch the login page for the CSRF cookie, then POST credentials with the token. */
    private HttpClient login() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newFixedThreadPool(16))
                .build();
        client.send(get("/login.html"), HttpResponse.BodyHandlers.discarding());
        String csrf = cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst()
                .orElseThrow(() -> new AssertionError("Login page did not issue an XSRF-TOKEN cookie"));
        HttpResponse<Void> r = client.send(formPost("/login", "username=admin&password="
                + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + csrf),
                HttpResponse.BodyHandlers.discarding());
        String location = r.headers().firstValue("Location").orElse("");
        if (r.statusCode() != 302 || location.contains("error") || location.contains("locked")) {
            throw new AssertionError("Login failed: " + r.statusCode() + " -> " + location);
        }
        return client;
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).GET().build();
    }

    private HttpRequest formPost(String path, String form) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
    }

    private static double pct(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.min(sorted.length - 1, Math.ceil(p / 100.0 * sorted.length) - 1);
        return sorted[Math.max(0, idx)] / 1000.0;
    }

    private static String render(Map<Integer, LongAdder> counts) {
        return Arrays.toString(counts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> (e.getKey() == -1 ? "transport-error" : "HTTP " + e.getKey()) + "=" + e.getValue().sum())
                .toArray());
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
