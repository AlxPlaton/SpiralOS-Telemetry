# SpiralOS Resilience: Deliberate Crash Attempts & Mitigations

For each failure category below, we deliberately tried to crash or break SpiralOS,
then built a defense into the platform and wrote a JUnit test that repeats the
attack on every build. If a defense ever regresses, the build fails.

Run everything:

```bash
./mvnw test
```

The real-server stress report is written to `target/stress-proof/server-stress-report.txt`.

The distributed attack (section 5) takes about a minute, so it is opt-in:

```bash
./mvnw test -Pattack
```

| Category | Test class | Tests |
|---|---|---|
| Platform / OS dependency (hardware APIs) | `ResilienceStaticLogicTests` | 10 |
| Platform / OS dependency (file system) | `PlatformResilienceTests` | 6 |
| Web browser dependency | `BrowserIndependenceTests` | 10 |
| Security | `SecurityResilienceTests` | 11 |
| Security: login throttling (found by the distributed attack) | `LoginThrottleTests` | 4 |
| Security: session flood (found by the distributed attack) | `AnonymousSessionTests` | 6 |
| Scaling users (in-process burst) | `ScalabilityStressTests` | 2 |
| Scaling users (real HTTP server, under attack) | `ServerStressProofTest` | 1 |
| Startup smoke test | `SpiralosTelemetryApplicationTests` | 1 |
| Trend history + anomaly alerts (Week 10) | `AnomalyDetectionTests` | 14 |
| Trend history, alert, power and disk endpoints | `TrendAlertApiTests` | 5 |
| Home-lab features: alert culprits, power estimate, disk forecast | `HomelabFeatureTests` | 12 |
| **Total in every build** | | **82 passing** |
| Distributed attack, 256 MB heap (`-Pattack`) | `DistributedAttackTest` | 1 |

---

## 1. Platform / Operating System dependency

**What broke it.** SpiralOS reads hardware data through OSHI, and OSHI behaves differently on each OS:

- An early build called `NetworkIF.getNetworkInterface()`. That method existed in the dev machine's
  OSHI version but not in the version resolved on the Ubuntu server, so the app crashed at startup.
- Loopback adapters have different names on each OS: `lo` (Linux), `lo0` (macOS) and
  "Software Loopback Interface 1" (Windows).
- Minimal server images can return `null` names or no IPv4 address at all.
- On the Windows dev machine, the Perflib performance-counter registry is damaged, so OSHI
  logs `Unable to locate English counter names` errors.
- The settings file may have Windows CRLF line endings, be hand-edited with bad values,
  be half-written after a power cut, or live in a directory that doesn't exist yet.

**What we implemented.**

- `TelemetrySampler.isLoopback()` only uses fields that are stable across OSHI versions and
  operating systems (name, display name, IPv4 address), and it is null-safe.
- The whole sampling pass is wrapped in a `try/catch`. If one pass fails on an OS-specific
  problem, it is logged and the last good snapshot keeps being served. The sampler thread never dies.
- `SettingsService` trims and parses each value separately, validates the whole file, and falls
  back to defaults when the file is corrupt or invalid. It writes to a `.tmp` file and then
  atomically moves it into place (with a non-atomic fallback for file systems that can't do that).
  It creates missing directories, and saves are `synchronized`.
- The app requires `SPIRALOS_ADMIN_PASSWORD` from the environment and refuses to start without
  one, so no OS-specific default credential ever ships.

**Evidence.** On the Windows dev machine the Perflib registry really is damaged. OSHI logs
errors, but every test passes and the sampler keeps producing fresh snapshots.

## 2. Web browser dependency

**What breaks it.** Browsers disagree when the server is vague. For example, one browser runs a
`.js` file sent with a missing or wrong MIME type while another blocks it. Browsers also cache
API responses differently, and throttle background-tab timers differently, which can pile requests up.

**What we implemented.**

- The frontend is plain JavaScript using the standard DOM and `fetch`: no framework, no build
  step, no vendor-prefixed APIs.
- Every API call uses `cache: 'no-store'`. The polling loop never overlaps itself and pauses on
  hidden tabs (`visibilitychange`).
- All rendering uses `textContent` and DOM nodes, never `innerHTML`, so a hostile process name
  or log line can't inject markup.
- The server sends explicit standard headers so every browser applies the same rules:
  `X-Content-Type-Options: nosniff`, a strict `Content-Security-Policy`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, and correct `Content-Type` for HTML, JS, CSS and JSON.
- A browser opening a protected page without a session gets a redirect to the login page, not an error.

## 3. Security

**Attacks attempted (all in `SecurityResilienceTests`, and again under load in `ServerStressProofTest`):**

| Attack | Result |
|---|---|
| Call the API with no session | 401, no data |
| Change settings without a CSRF token | 403 |
| Brute-force the login (6 attempts) | 6th attempt locked out, even with the correct password |
| `limit=0` / `limit=100000` on processes (resource exhaustion) | 400 |
| `source=/etc/shadow`, `source=../../windows/win.ini` (arbitrary file read) | 400 |
| 10,000-character search string | 400 |
| `sort=name;DROP TABLE` | 400 |
| Malformed / truncated JSON | 400, no stack trace |
| Type-confused JSON (`"<script>"`, integer overflow) | 400 |
| `refreshIntervalMs=1` (turn every browser into a DoS client) | 400 |
| `limit=abc` to force an exception | 400, response body leaks no internals |

**What we implemented.** Login is required for everything except the login page itself. Passwords
are hashed with BCrypt (cost 12). Session cookies are HttpOnly and SameSite=Strict, and every
state-changing request needs a CSRF token. Each IP address gets 5 login attempts before a
15-minute lockout, and attempts are reserved atomically before the password is checked. A global
budget of 30 attempts per minute applies to addresses that have never logged in (see section 5).
Anonymous requests never create a server session. Clients choose a
log source by an allow-listed ID and never send a file path. Every parameter has a bounded range.
`server.error.include-message/stacktrace=never` keeps internals out of error pages.

## 4. Scaling up the number of users

**What broke it.** The original design read OSHI hardware data inside each HTTP request, including
a 1-second blocking CPU measurement. With N users, that meant N blocking hardware reads at once,
which could exhaust Tomcat's worker threads during a burst.

**What we implemented.** Hardware sampling now runs on one background thread on a fixed schedule
and publishes an immutable `Snapshot` through a `volatile` field. Requests only read that cached
snapshot, so the backend costs the same per request whether there is 1 user or 500.

**Server proof: `ServerStressProofTest`.** This test boots the real embedded Tomcat server and
attacks it over real HTTP: 20 genuinely logged-in sessions (real BCrypt, CSRF and cookies) and
200 concurrent users for 15 seconds, while an attacker sends unauthenticated, brute-force,
traversal and out-of-range requests at the same time. A run on the dev machine (Windows 11,
Java 21, 16 cores):

```
Requests completed  : 120,344 in 15.1s  (7952 req/s)
Status breakdown    : [HTTP 200=120344]
5xx server errors   : 0
Transport failures  : 0  (refused / reset / timed-out connections)
Latency (ms)        : p50 2.5 | p95 109.5 | p99 163.8 | max 334.7
Attack requests     : 14,579  -> [HTTP 400=7289, HTTP 401=4860, HTTP 403=2430]
Attacks that got 2xx: 0   Attacks that caused 5xx: 0
Heap used (MB)      : before 30 | peak during 727 | after GC 75
Tomcat worker thr.  : 200  (pool limit 200 - requests queued, never refused)
Sampler snapshots   : 7 fresh snapshots produced during load
App ERROR log lines : 0
Post-load health    : HTTP 200
```

How to read it:

- Tomcat hit its 200-thread limit, but requests waited in the queue and were never refused.
- Heap returned to 75 MB after GC, so there is no leak.
- The sampler kept producing a fresh snapshot every 2 seconds, so request traffic did not starve it.
- The app logged no errors, and the server was still healthy afterwards.
- The JVM thread peak includes the load generator's own threads, because the client runs in the same JVM.

Exact numbers will differ on other hardware. The test fails if any legitimate request gets a 5xx,
any connection is dropped, any attack succeeds or causes a 5xx, the app logs an ERROR, the sampler
stalls, or the server is unhealthy afterwards.

## 5. Distributed attack: trying to crash it on purpose

Every attack above came from a single IP address, and per-IP defenses look perfect against one
attacker. So `DistributedAttackTest` simulates a botnet. Windows and Linux route the whole
`127.0.0.0/8` block to the local machine, so each bot binds its socket to its own address
(`127.1.2.3`, `127.1.2.4`, ...). The server genuinely sees thousands of different clients over
real TCP. The test runs on a 256 MB heap, closer to a small server than a dev PC. A logged-in
admin keeps refreshing the dashboard throughout, so we measure what a real user experiences.

The first run **found three real weaknesses**, all now fixed:

| Attack | Before the fix | After the fix |
|---|---|---|
| 60 simultaneous guesses from one IP | **48-60 passwords checked.** The "locked?" check and the failure counter were separate steps, so a burst slipped past both. | **5 checked**, 55 blocked |
| 400-IP botnet, 8 guesses each | **2,000 guesses in 25 s.** That's ~192,000 per day, and it grows by 5 for every extra IP. | **24 guesses checked**, 3,176 blocked. Capped at 30/min no matter how big the botnet is. |
| 20,000 anonymous page hits | **20,000 server sessions** (~1.5 KB each, held 30 min). At this rate, a 256 MB server runs out of memory in roughly 15 s (estimated). | **0 sessions**, heap unchanged (30 MB to 30 MB) |
| ~600-900 half-open "slowloris" connections | Admin unaffected | Admin unaffected |
| Recovery after the attack | Server recovered | Server recovered |

**The fixes (in `SecurityConfig`):**

1. **Atomic lockout.** `LoginAttempts.tryAcquire()` reserves an attempt in one atomic
   `ConcurrentHashMap.compute()` call *before* BCrypt runs. Simultaneous guesses can no longer
   all pass the check before any failure is counted.
2. **Global login budget.** Addresses that have never logged in successfully share a budget of
   30 attempts per minute (`spiralos.login.global-max-per-minute`). This also caps BCrypt CPU cost
   during an attack, and the lockout map can only grow by about 30 new IPs per minute. Rejected
   users see "Logins are paused briefly due to unusual activity."
   - *Trade-off:* during an attack, a **new** device has to wait up to a minute to log in.
   - Addresses that have logged in before are exempt, and existing sessions are never affected,
     so the admin keeps access.
3. **No sessions for anonymous visitors.** A `NullRequestCache` replaces Spring Security's
   "remember the page you wanted" feature. It created a session for every anonymous request,
   and SpiralOS never used it, because login always lands on `/`.

**What the test fails on.** It fails if a bot ever authenticates, if one IP gets more than
5 guesses, if the botnet beats the global budget, if anonymous traffic creates sessions, or if the
server isn't fully healthy after the attack. Slowdowns *during* the attack are reported but don't
fail the test.

**Known limits of this simulation:**

- Only about 600-900 of the 9,000 slowloris connections opened. The operating system's connection
  queue refused the rest of the burst, so Tomcat's 8,192-connection limit was never actually
  reached. Testing that needs several attacking machines.
- The attackers share a JVM and a CPU with the server. A real botnet doesn't, so this test
  under-states CPU pressure on the attacker side and over-states it on the server side.
- With one machine, Windows' ~16k outgoing ports limit how many connections the attacker can make.
  The test closes its sockets with `SO_LINGER=0` so the attacker doesn't run out first.

## 6. Running the tests on the real Linux server

The tests above were written and run on Windows. Running them on the actual Ubuntu server
(4 cores, 7 GB RAM, Java 21) **found two problems that Windows had hidden**, both now fixed.

| Problem on Linux | Why Windows hid it | Fix |
|---|---|---|
| The attack test crashed with `OutOfMemoryError` during the slowloris phase | Windows refused most of the 9,000 slow connections (only ~600-900 opened). Linux accepted them up to Tomcat's 8,192 limit, and each one costs ~20-30 KB of buffers: more than the 256 MB heap | `server.tomcat.max-connections=1000` caps that at ~30 MB. Past it, new connections wait in the OS queue instead of crashing the server |
| The background sampler froze under load: 2 readings, then the same stale reading even after the load ended | Linux shares CPU roughly equally per thread. With ~765 threads busy, the sampler got about 0.5% of 4 cores, not enough to finish a full `/proc` scan of every process | The sampler is split: CPU, memory and network (a few file reads) update every 2 s on their own thread; the process-table scan runs on a second thread; network interfaces are re-discovered once a minute instead of every sample |

**Results on the server (Linux, 4 cores, 2-Nov-2026):**

| | Before the fix | After the fix |
|---|---|---|
| Stress test: sampler readings during 15 s of load | 2, then frozen (stale even after the load) | 8, kept updating |
| Stress test: requests / errors | 20,367 / 0 | 21,012 / 0 |
| Attack test | Crashed with `OutOfMemoryError` | Passed |
| Slowloris connections that got in | (crashed) | 3,239 of 9,000; ~1,160 held at the cap, 2,077 closed by the server |
| Heap peak (256 MB limit) | Exhausted | 183 MB |
| Admin requests failed during the 25 s slowloris | (crashed) | 21 of 271 (timed out waiting for a free slot) |
| Admin after the attack | (crashed) | 0 failures, session and fresh login work |
| Security phases | (crashed) | Unchanged: 5 race guesses, 24 botnet guesses, 0 bot logins, 0 sessions |

The slowloris phase's connect timeout was also cut from 3 s to 0.5 s: once the cap is full,
Linux silently drops further attempts, and waiting 3 s for each one made that phase take
about 3 minutes without changing the result.

**Trade-off:** during a slowloris attack, new visitors may now have to wait for a free
connection slot. The server stays up and recovers when the attack stops. The full fix is a
reverse proxy with per-IP connection limits in front of SpiralOS (Future Work).

**Deployment note:** start the server with `-XX:+ExitOnOutOfMemoryError` under a service
manager that restarts it (e.g. systemd `Restart=always`), so that if memory ever does run out the
app restarts cleanly instead of limping along half-broken.
