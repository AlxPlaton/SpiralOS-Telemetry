# SpiralOS

A real-time system telemetry dashboard. A Spring Boot backend reads live hardware and process data with [OSHI](https://github.com/oshi/oshi) and serves it as JSON behind a login; a static HTML/CSS/JS frontend polls it and renders a NOC-style monitoring panel.

## Features

- **Dashboard**: live CPU, memory and network cards with sparklines and warn/critical colouring, plus the top processes by current CPU load.
- **Processes page**: every running process with PID, name, user, CPU %, memory, threads and state. Sortable columns, text filter, and a **total CPU %** across all processes next to the system CPU figure.
- **Logs page**: SpiralOS' own application log, plus any server log files you whitelist (e.g. Pi-hole). Level filter, search, line count, follow and pause.
- **Settings page**: refresh interval, CPU/memory warn and critical thresholds, and row/line counts. Saved on the server and applied to every page.
- **Secure by default**: login required, CSRF protection, brute-force lockout, strict CSP. See [`SECURITY.md`](SECURITY.md).
- **Connection badge** flips to `[ OFFLINE ]` if the backend stops answering.

## Project structure

```
src/main/java/spiralos/
  SecurityConfig.java        login, sessions, CSRF, headers, lockout
  TelemetrySampler.java      one background thread reads OSHI, caches results
  TelemetryController.java   GET /api/v1/telemetry
  ProcessController.java     GET /api/v1/processes
  LogBuffer.java / LogController.java       GET /api/v1/logs[/sources]
  AppSettings.java / SettingsService.java / SettingsController.java   /api/v1/settings
  AuthController.java        GET /api/v1/auth/me
src/main/resources/static/
  index.html processes.html logs.html settings.html login.html
  style.css common.js dashboard.js processes.js logs.js settings.js login.js
```

## Configuration

Add to `pom.xml`:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

Add to `src/main/resources/application.properties`:

```properties
server.error.include-message=never
server.error.include-stacktrace=never
server.error.whitelabel.enabled=false
server.servlet.session.cookie.name=SPIRALOS_SESSION
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.same-site=strict
server.servlet.session.cookie.secure=${SPIRALOS_COOKIE_SECURE:false}
server.servlet.session.timeout=30m
server.servlet.session.tracking-modes=cookie

spiralos.sampler.interval-ms=2000
spiralos.settings.path=./spiralos-settings.properties
# Optional extra log files shown on the Logs page (id=path, comma separated):
# spiralos.logs.files=pihole=/var/log/pihole/pihole.log
```

Set the admin credentials as environment variables (never commit them):

```bash
export SPIRALOS_ADMIN_USER=admin
export SPIRALOS_ADMIN_PASSWORD='at-least-12-characters'
```

## Running

```bash
./mvnw package
java -jar target/spiralos-telemetry-0.0.1-SNAPSHOT.jar
```

Open `http://<host>:8080` and sign in. In Eclipse, set the same variables under *Run Configurations > Environment*.

## API

All endpoints require an authenticated session. State-changing requests also need the `X-XSRF-TOKEN` header (the frontend does this automatically).

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/telemetry` | CPU %, memory, network rates, process/thread totals |
| GET | `/api/v1/processes?sort=cpu&order=desc&limit=100&q=` | Process list plus `totalCpuPercent` and `systemCpuPercent` |
| GET | `/api/v1/logs/sources` | Available log sources |
| GET | `/api/v1/logs?source=app&lines=200&level=ALL&q=` | Log lines |
| GET / PUT | `/api/v1/settings` | Read / update settings |
| POST | `/api/v1/settings/reset` | Restore default settings |
| GET | `/api/v1/auth/me` | Current user |

## Notes

- Per-process CPU is measured **between samples** (current load), normalised by logical core count, so it is on the same 0-100 scale as the system CPU. PID 0 (System Idle Process) is excluded.
- "Total CPU" is the sum of per-process load; it can differ slightly from system CPU because of sampling timing and kernel time.
- Network rates exclude the loopback interface.

## License

MIT, see `LICENSE.txt`.
