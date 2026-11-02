package spiralos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.Socket;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

/**
 * FAILURE CATEGORY: Security + scaling - a simulated DISTRIBUTED attack that
 * deliberately tries to crash, lock up or break into the real server.
 *
 * Windows and Linux route the whole 127.0.0.0/8 block to loopback, so each
 * "bot" binds its socket to its own address (127.1.2.3, 127.1.2.4, ...) and
 * the server genuinely sees thousands of different client IPs over real TCP.
 * That defeats any per-IP defense exactly as a real botnet would.
 *
 * Phases (a logged-in admin keeps polling the dashboard API through all of them,
 * so we measure what a real user experiences during the attack):
 *   1. Botnet credential stuffing - 400 IPs x 8 password guesses each
 *   2. Lockout race               - 60 simultaneous guesses from ONE IP
 *   3. Session flood              - 20,000 cookie-less page hits from 5,000 IPs
 *   4. Slowloris                  - 9,000 connections that never finish their headers
 *   5. Recovery                   - attack stops; is the server back to normal?
 *
 * "Crashed" here means: an attacker got in, the JVM ran out of memory, or the
 * server did not come back after the attack stopped. Those fail the test.
 * Slowdowns and dropped requests DURING an attack are measured and reported as
 * findings rather than failures, because those are exactly what we are trying
 * to discover.
 *
 * Opt-in (takes ~2 minutes, runs with a 256 MB heap):  ./mvnw test -Pattack
 * Report: target/stress-proof/distributed-attack-report.txt
 */
@Tag("attack")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "SPIRALOS_ADMIN_PASSWORD=test-only-password-123",
        "spiralos.settings.path=target/test-settings.properties" })
class DistributedAttackTest {

    private static final String PASSWORD = "test-only-password-123";
    private static final int GLOBAL_LOGINS_PER_MINUTE = 30; // spiralos.login.global-max-per-minute default
    private static final int BOTS = 400;
    private static final int GUESSES_PER_BOT = 8;
    private static final int RACE_THREADS = 60;
    private static final int SESSION_FLOOD = 20_000;
    private static final int SLOW_CONNECTIONS = 9_000;
    private static final Duration SLOW_HOLD = Duration.ofSeconds(25);

    static final AtomicInteger SESSIONS_CREATED = new AtomicInteger();
    static final AtomicInteger SESSIONS_LIVE = new AtomicInteger();

    /** Counts HTTP sessions on the server side, so session-flood growth is measured, not guessed. */
    @TestConfiguration
    static class SessionCounter {
        @Bean
        HttpSessionListener sessionCounter() {
            return new HttpSessionListener() {
                @Override
                public void sessionCreated(HttpSessionEvent e) {
                    SESSIONS_CREATED.incrementAndGet();
                    SESSIONS_LIVE.incrementAndGet();
                }

                @Override
                public void sessionDestroyed(HttpSessionEvent e) {
                    SESSIONS_LIVE.decrementAndGet();
                }
            };
        }
    }

    @Autowired
    private Environment env;

    private int port;
    private String base;
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();

    /** What the logged-in admin experienced during each phase. */
    private static final class PhaseStats {
        final LongAdder ok = new LongAdder();
        final LongAdder failed = new LongAdder();
        final List<Long> latencyMs = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong peakHeapMb = new AtomicLong();
    }

    private final Map<String, PhaseStats> phases = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile String phase;
    private final List<String> report = new ArrayList<>();

    @Test
    @DisplayName("Distributed attack (botnet stuffing, lockout race, session flood, slowloris): never breached, always recovers")
    void distributedAttack() throws Exception {
        port = Integer.parseInt(env.getProperty("local.server.port"));
        base = "http://localhost:" + port;
        HttpClient admin = login();

        line("==================== SPIRALOS DISTRIBUTED ATTACK REPORT ====================");
        line("Run at        : " + Instant.now());
        line("Platform      : " + System.getProperty("os.name") + " / Java " + System.getProperty("java.version")
                + " / " + Runtime.getRuntime().availableProcessors() + " cores / max heap "
                + memory.getHeapMemoryUsage().getMax() / (1024 * 1024) + " MB");
        line("Server        : embedded Tomcat on " + base + " (attacker and server share this JVM's heap)");

        AtomicInteger stop = new AtomicInteger();
        Thread monitor = Thread.ofPlatform().daemon().start(() -> monitorLoop(admin, stop));

        setPhase("0. baseline (no attack)");
        Thread.sleep(3000);

        // race first: it tests the per-IP lockout on its own, before the botnet spends the global budget
        long raceGuesses = lockoutRace();
        long[] stuffing = credentialStuffing();
        long botSuccesses = stuffing[0];
        long botGuessesChecked = stuffing[1];
        long stuffingSeconds = stuffing[2];
        int sessionsFromFlood = sessionFlood();
        slowloris();

        setPhase("5. recovery (attack stopped)");
        Thread.sleep(5000);
        stop.set(1);
        monitor.join(10_000);

        HttpResponse<String> health = admin.send(get("/api/v1/telemetry"), HttpResponse.BodyHandlers.ofString());
        boolean freshLoginWorks;
        try {
            login();
            freshLoginWorks = true;
        } catch (AssertionError | IOException e) {
            freshLoginWorks = false;
        }
        PhaseStats recovery = phases.get("5. recovery (attack stopped)");

        line("");
        line("--- What the logged-in admin saw (dashboard API polled every 200 ms, 5 s timeout) ---");
        line(String.format("%-46s %6s %7s %9s %9s %9s", "phase", "ok", "failed", "p95 ms", "max ms", "heap MB"));
        phases.forEach((name, s) -> {
            long[] lat;
            synchronized (s.latencyMs) {
                lat = s.latencyMs.stream().mapToLong(Long::longValue).sorted().toArray();
            }
            line(String.format("%-46s %6d %7d %9d %9d %9d", name, s.ok.sum(), s.failed.sum(),
                    pct(lat, 95), lat.length == 0 ? 0 : lat[lat.length - 1], s.peakHeapMb.get()));
        });
        line("");
        line("--- Recovery ---");
        line("Existing admin session : HTTP " + health.statusCode());
        line("Fresh admin login      : " + (freshLoginWorks ? "works" : "FAILED"));
        line("Recovery-phase failures: " + recovery.failed.sum());
        line("==========================================================================");

        String text = String.join(System.lineSeparator(), report);
        System.out.println(text);
        Path out = Path.of("target", "stress-proof", "distributed-attack-report.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text + System.lineSeparator());

        assertEquals(0, botSuccesses, "No bot may ever authenticate");
        assertTrue(raceGuesses <= 5, "Simultaneous guesses from one IP must not get past the 5-attempt lockout");
        assertTrue(botGuessesChecked <= GLOBAL_LOGINS_PER_MINUTE * (stuffingSeconds / 60 + 2),
                "A botnet must not get more guesses than the global per-minute budget");
        assertEquals(0, sessionsFromFlood, "Anonymous requests must not create server sessions");
        assertEquals(200, health.statusCode(), "Existing sessions must work again once the attack stops");
        assertTrue(freshLoginWorks, "A legitimate user must be able to log in after the attack");
        assertEquals(0, recovery.failed.sum(), "No dashboard request may fail once the attack has stopped");
    }

    // ---------------------------------------------------------------- phase 1

    private long[] credentialStuffing() throws Exception {
        setPhase("2. botnet credential stuffing (400 IPs)");
        LongAdder evaluated = new LongAdder();
        LongAdder locked = new LongAdder();
        LongAdder busy = new LongAdder();
        LongAdder succeeded = new LongAdder();
        LongAdder other = new LongAdder();
        AtomicInteger maxEvaluatedPerBot = new AtomicInteger();

        long t0 = System.nanoTime();
        runAll(64, BOTS, b -> {
            String ip = botIp(b);
            String token = csrfToken(ip);
            int mine = 0;
            for (int g = 0; g < GUESSES_PER_BOT; g++) {
                Resp r = raw(ip, loginRequest(token, "wrong-" + b + "-" + g));
                String loc = r.header("Location");
                if (r.status() == 302 && loc.contains("?error")) {
                    evaluated.increment();
                    mine++;
                } else if (r.status() == 302 && loc.contains("?locked")) {
                    locked.increment();
                } else if (r.status() == 302 && loc.contains("?busy")) {
                    busy.increment();
                } else if (r.status() == 302) {
                    succeeded.increment();
                } else {
                    other.increment();
                }
            }
            maxEvaluatedPerBot.accumulateAndGet(mine, Math::max);
        });
        double secs = (System.nanoTime() - t0) / 1e9;

        line("");
        line("--- Phase 2: botnet credential stuffing ---");
        line(String.format("Bots / guesses sent      : %d IPs x %d = %,d in %.1fs", BOTS, GUESSES_PER_BOT,
                BOTS * GUESSES_PER_BOT, secs));
        line("Guesses actually checked : " + evaluated.sum() + "  (max per IP: " + maxEvaluatedPerBot.get() + ")");
        line("Blocked by per-IP lockout: " + locked.sum());
        line("Blocked by global limit  : " + busy.sum() + "  (budget: " + GLOBAL_LOGINS_PER_MINUTE
                + " attempts/min from never-logged-in IPs)");
        line("Bot logins that succeeded: " + succeeded.sum() + "   other responses: " + other.sum());
        if (evaluated.sum() > GLOBAL_LOGINS_PER_MINUTE * (long) (secs / 60 + 2)) {
            line(String.format("FINDING: per-IP lockout only - %d IPs got %,d guesses in. A botnet of %d IPs gets"
                    + " ~%,d guesses per day.", BOTS, evaluated.sum(), BOTS, BOTS * 5L * 4 * 24));
        } else {
            line(String.format("FIXED: the botnet's guess rate is capped at %d/min regardless of its size"
                    + " (max ~%,d per day), instead of growing by 5 per IP.",
                    GLOBAL_LOGINS_PER_MINUTE, GLOBAL_LOGINS_PER_MINUTE * 60L * 24));
        }
        return new long[] { succeeded.sum(), evaluated.sum(), (long) secs };
    }

    // ---------------------------------------------------------------- phase 2

    private long lockoutRace() throws Exception {
        setPhase("1. lockout race (60 parallel, 1 IP)");
        String ip = "127.0.0.77";
        String token = csrfToken(ip);
        LongAdder evaluated = new LongAdder();
        LongAdder locked = new LongAdder();
        LongAdder busy = new LongAdder();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(RACE_THREADS);
        for (int i = 0; i < RACE_THREADS; i++) {
            int n = i;
            pool.submit(() -> {
                gate.await();
                Resp r = raw(ip, loginRequest(token, "race-" + n));
                if (r.header("Location").contains("?error")) {
                    evaluated.increment();
                } else if (r.header("Location").contains("?locked")) {
                    locked.increment();
                } else if (r.header("Location").contains("?busy")) {
                    busy.increment();
                }
                return null;
            });
        }
        gate.countDown();
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.MINUTES);

        line("");
        line("--- Phase 1: lockout race (60 simultaneous guesses from one IP) ---");
        line("Guesses actually checked : " + evaluated.sum() + "  (the lockout is supposed to allow 5)");
        line("Blocked by lockout       : " + locked.sum() + "   blocked by global limit: " + busy.sum());
        if (evaluated.sum() > 5) {
            line("FINDING: the lockout check and the attempt counter are not atomic, so one IP got "
                    + evaluated.sum() + " guesses instead of 5.");
        } else {
            line("FIXED: attempts are reserved atomically before the password check; the burst got "
                    + evaluated.sum() + " guesses (before the fix: 48-60).");
        }
        return evaluated.sum();
    }

    // ---------------------------------------------------------------- phase 3

    private int sessionFlood() throws Exception {
        setPhase("3. session flood (20k anon hits, 5k IPs)");
        System.gc();
        long heapBefore = heapMb();
        int createdBefore = SESSIONS_CREATED.get();
        LongAdder redirects = new LongAdder();
        LongAdder other = new LongAdder();

        long t0 = System.nanoTime();
        runAll(64, SESSION_FLOOD, i -> {
            Resp r = raw(botIp(1_000 + i % 5_000),
                    "GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            (r.status() == 302 ? redirects : other).increment();
        });
        double secs = (System.nanoTime() - t0) / 1e9;
        System.gc();
        long heapAfter = heapMb();
        int created = SESSIONS_CREATED.get() - createdBefore;

        line("");
        line("--- Phase 3: session flood (no cookies, never logs in) ---");
        line(String.format("Requests                 : %,d in %.1fs, redirected to login: %,d, other: %d",
                SESSION_FLOOD, secs, redirects.sum(), other.sum()));
        line(String.format("Server sessions created  : %,d   (live now: %,d, they expire after 30 min)", created,
                SESSIONS_LIVE.get()));
        line("Heap after GC            : " + heapBefore + " MB -> " + heapAfter + " MB");
        if (created > 0) {
            line(String.format("FINDING: every anonymous page hit creates a server session (Spring Security saves"
                    + " the requested URL in it before redirecting to login). An attacker can grow server memory"
                    + " without ever knowing the password: ~%.1f KB retained per hit here.",
                    Math.max(0, heapAfter - heapBefore) * 1024.0 / created));
        } else {
            line("FIXED: anonymous requests no longer create sessions (before the fix: 20,000 sessions,"
                    + " ~1.5 KB each).");
        }
        return created;
    }

    // ---------------------------------------------------------------- phase 4

    private void slowloris() throws Exception {
        setPhase("4. slowloris (9k half-open connections)");
        List<Socket> held = Collections.synchronizedList(new ArrayList<>());
        LongAdder openFailed = new LongAdder();
        Map<String, LongAdder> failReasons = new java.util.concurrent.ConcurrentHashMap<>();

        long t0 = System.nanoTime();
        runAll(128, SLOW_CONNECTIONS, i -> {
            Socket s = new Socket();
            try {
                s.setSoLinger(true, 0);
                s.bind(new InetSocketAddress(botIp(10_000 + i), 0));
                // short timeout: once Tomcat's connection cap and the OS queue are full, Linux drops
                // further connection attempts silently instead of refusing them, and a 3 s wait per
                // attempt would stall this phase for minutes without changing the result
                s.connect(new InetSocketAddress("127.0.0.1", port), 500);
                s.getOutputStream().write("GET /login.html HTTP/1.1\r\nHost: localhost\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
                held.add(s);
            } catch (IOException e) {
                openFailed.increment();
                failReasons.computeIfAbsent(e.getClass().getSimpleName() + ": " + e.getMessage(),
                        k -> new LongAdder()).increment();
                s.close();
            }
        });
        double openSecs = (System.nanoTime() - t0) / 1e9;

        // keep every connection alive by trickling one header line every 5 s, never finishing the request
        int droppedByServer = 0;
        Instant until = Instant.now().plus(SLOW_HOLD);
        while (Instant.now().isBefore(until)) {
            Thread.sleep(5000);
            synchronized (held) {
                for (Socket s : held) {
                    if (s.isClosed()) {
                        continue;
                    }
                    try {
                        OutputStream o = s.getOutputStream();
                        o.write("X-Keep-Alive: 1\r\n".getBytes(StandardCharsets.ISO_8859_1));
                        o.flush();
                    } catch (IOException e) {
                        droppedByServer++;
                        s.close();
                    }
                }
            }
        }
        long stillOpen;
        synchronized (held) {
            stillOpen = held.stream().filter(s -> !s.isClosed()).count();
            for (Socket s : held) {
                s.close();
            }
        }
        PhaseStats s = phases.get(phase);

        line("");
        line("--- Phase 4: slowloris (connections that never finish sending headers) ---");
        line(String.format("Slow connections opened  : %,d of %,d in %.1fs (failed to connect: %,d)",
                held.size(), SLOW_CONNECTIONS, openSecs, openFailed.sum()));
        failReasons.forEach((reason, n) -> line("  connect failure        : " + n.sum() + " x " + reason));
        line(String.format("Held open for %ds        : %,d still open at the end, %,d dropped by the server",
                SLOW_HOLD.toSeconds(), stillOpen, droppedByServer));
        if (s.failed.sum() > 0) {
            line("FINDING: while the slow connections were held, " + s.failed.sum()
                    + " of the admin's dashboard requests failed. Tomcat's connection limit ("
                    + env.getProperty("server.tomcat.max-connections", "8192") + ") was"
                    + " exhausted by clients that never finish a request, so new connections had to wait."
                    + " The server stayed up; a reverse proxy with per-IP limits is the full fix.");
        } else {
            line("Tomcat kept serving the admin while the slow connections were held.");
        }
    }

    // ---------------------------------------------------------------- monitor

    private void monitorLoop(HttpClient admin, AtomicInteger stop) {
        while (stop.get() == 0) {
            PhaseStats s = phases.get(phase);
            long t0 = System.nanoTime();
            try {
                HttpResponse<Void> r = admin.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/v1/telemetry"))
                                .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                (r.statusCode() == 200 ? s.ok : s.failed).increment();
            } catch (Exception e) {
                s.failed.increment();
            }
            s.latencyMs.add((System.nanoTime() - t0) / 1_000_000);
            s.peakHeapMb.accumulateAndGet(heapMb(), Math::max);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private void setPhase(String name) {
        phases.put(name, new PhaseStats());
        phase = name;
    }

    private void line(String s) {
        report.add(s);
    }

    private long heapMb() {
        return memory.getHeapMemoryUsage().getUsed() / (1024 * 1024);
    }

    /** Maps bot number n to a unique loopback address 127.a.b.c (never 127.0.0.x, which the admin uses). */
    private static String botIp(int n) {
        return "127." + (1 + n / 62_500) + "." + (1 + (n / 250) % 250) + "." + (1 + n % 250);
    }

    @FunctionalInterface
    private interface Job {
        void run(int i) throws Exception;
    }

    private static void runAll(int threads, int count, Job job) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < count; i++) {
            int n = i;
            pool.submit(() -> {
                try {
                    job.run(n);
                } catch (Exception ignored) {
                    // an attack request failing is part of what is being measured, not a test error
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.MINUTES);
    }

    private record Resp(int status, List<String> headers) {
        String header(String name) {
            String prefix = name.toLowerCase() + ":";
            return headers.stream().filter(h -> h.toLowerCase().startsWith(prefix))
                    .map(h -> h.substring(prefix.length()).trim()).findFirst().orElse("");
        }
    }

    /** Sends one raw HTTP/1.1 request from a specific source IP and reads the status line + headers. */
    private Resp raw(String sourceIp, String request) throws IOException {
        try (Socket s = new Socket()) {
            // reset on close instead of leaving the port in TIME_WAIT: otherwise the ATTACKER runs out
            // of ephemeral ports (~16k on Windows) long before the server runs out of anything
            s.setSoLinger(true, 0);
            s.bind(new InetSocketAddress(sourceIp, 0));
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(30_000);
            s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = in.readLine();
            if (statusLine == null) {
                throw new IOException("connection closed without a response");
            }
            List<String> headers = new ArrayList<>();
            for (String h = in.readLine(); h != null && !h.isEmpty(); h = in.readLine()) {
                headers.add(h);
            }
            return new Resp(Integer.parseInt(statusLine.split(" ")[1]), headers);
        }
    }

    private String csrfToken(String sourceIp) throws IOException {
        Resp r = raw(sourceIp, "GET /login.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        return r.headers().stream()
                .filter(h -> h.toLowerCase().startsWith("set-cookie:") && h.contains("XSRF-TOKEN="))
                .map(h -> h.substring(h.indexOf("XSRF-TOKEN=") + 11).split(";")[0])
                .findFirst().orElseThrow(() -> new IOException("no XSRF-TOKEN cookie issued"));
    }

    private static String loginRequest(String token, String password) {
        String body = "username=admin&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                + "&_csrf=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        return "POST /login HTTP/1.1\r\nHost: localhost\r\nCookie: XSRF-TOKEN=" + token + "\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: " + body.length() + "\r\n"
                + "Connection: close\r\n\r\n" + body;
    }

    /** Real form login from 127.0.0.1 (an address no bot uses). */
    private HttpClient login() throws IOException, InterruptedException {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        client.send(get("/login.html"), HttpResponse.BodyHandlers.discarding());
        String csrf = cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst()
                .orElseThrow(() -> new AssertionError("no XSRF-TOKEN cookie"));
        HttpResponse<Void> r = client.send(HttpRequest.newBuilder(URI.create(base + "/login"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password="
                        + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + csrf))
                .build(), HttpResponse.BodyHandlers.discarding());
        String loc = r.headers().firstValue("Location").orElse("");
        if (r.statusCode() != 302 || loc.contains("error") || loc.contains("locked")) {
            throw new AssertionError("Admin login failed: " + r.statusCode() + " -> " + loc);
        }
        return client;
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).GET().build();
    }

    private static long pct(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[(int) Math.max(0, Math.min(sorted.length - 1, Math.ceil(p / 100.0 * sorted.length) - 1))];
    }
}
