# SpiralOS

A real-time server telemetry dashboard for home labs and small servers. A Spring Boot backend reads live hardware and process data with [OSHI](https://github.com/oshi/oshi) and serves it as JSON behind a login; a static HTML/CSS/JS frontend polls it and renders the dashboard in any browser.

## Features

- **Dashboard**: live CPU, memory, network and **power draw** cards with sparklines and green / amber / red status colouring, plus the top processes by CPU.
- **Performance trends**: CPU, memory and network charts for the last 5, 15 or 60 minutes, with threshold lines, a hover readout and markers where alerts fired.
- **Alerts**: sustained-threshold and sudden-spike detection. Each alert records the **top processes at the moment it fired**, so you can see what caused it.
- **Power and cost estimate**: watts now, the hourly average and the estimated monthly electricity cost, based on your server's idle / full-load watts and your price per kWh.
- **Storage**: every local drive with its usage and a **time-until-full forecast** from the last 12 hours.
- **Processes page**: every running process with PID, name, user, CPU %, memory, threads and state. Sortable columns and a text filter.
- **Logs page**: SpiralOS' own log, plus any server log files you whitelist. Level filter, search, line count, follow and pause.
- **Settings page**: refresh interval, alert thresholds, power estimate and display options, saved on the server.
- **Secure by default**: login required, brute-force lockout, CSRF protection and a strict Content Security Policy. See [`SECURITY.md`](SECURITY.md).

---

## Quick start (fresh computer)

These steps work on a computer that has never run SpiralOS. They take about 10 minutes, most of it the first build downloading dependencies.

### 1. Prerequisites

| Need | Version | Check it with |
|---|---|---|
| **Java JDK** | **21 or newer** (a JDK, not just a JRE) | `java -version` and `javac -version` |
| Internet access | For the first build (downloads Maven and libraries) | |
| A web browser | Chrome, Edge or Firefox | |

You do **not** need to install Maven. The project includes the Maven Wrapper (`mvnw` / `mvnw.cmd`), which downloads the right version automatically. Git is optional (you can download the ZIP instead).

**If Java 21 is missing:**
- **With admin rights:** install *Eclipse Temurin JDK 21* from https://adoptium.net (on Windows, tick "Set JAVA_HOME" in the installer).
- **Without admin rights (e.g. a lab computer):** download the Temurin JDK 21 **.zip** for Windows x64 from https://adoptium.net/temurin/releases/?version=21, unzip it (for example to `C:\Users\<you>\jdk-21`), and point this terminal window at it:
  ```powershell
  $env:JAVA_HOME = "C:\Users\<you>\jdk-21"   # the folder that contains bin\java.exe
  $env:Path = "$env:JAVA_HOME\bin;$env:Path"
  java -version                                # should now say 21
  ```
- **Ubuntu / Debian:** `sudo apt install openjdk-21-jdk`

### 2. Download the code

**Option A: ZIP (no Git needed).** Open https://github.com/AlxPlaton/SpiralOS-Telemetry, click **Code → Download ZIP**, and extract it. Open a terminal **in the extracted folder** (the one that contains `pom.xml`).

**Option B: Git.**
```bash
git clone https://github.com/AlxPlaton/SpiralOS-Telemetry.git
cd SpiralOS-Telemetry
```

### 3. Build and run the tests

**Windows (PowerShell):**
```powershell
.\mvnw.cmd clean package
```

**Linux / macOS:**
```bash
chmod +x mvnw          # needed after a ZIP download
./mvnw clean package
```

This compiles the project and runs the full test suite (**82 tests**). It should end with `Tests run: 82, Failures: 0, Errors: 0` and **`BUILD SUCCESS`**, and produce `target/spiralos-telemetry-0.0.1-SNAPSHOT.jar`. The first build takes a few minutes while it downloads; after that a full build with tests takes well under a minute.

### 4. Set the admin password

SpiralOS has no default password. It **refuses to start** unless you set one of **at least 12 characters** as an environment variable. Set it **in the same terminal window** you will start SpiralOS from.

**Windows (PowerShell):**
```powershell
$env:SPIRALOS_ADMIN_USER = "admin"
$env:SPIRALOS_ADMIN_PASSWORD = "choose-a-long-password"
```

**Windows (Command Prompt):**
```bat
set SPIRALOS_ADMIN_USER=admin
set SPIRALOS_ADMIN_PASSWORD=choose-a-long-password
```

**Linux / macOS:**
```bash
export SPIRALOS_ADMIN_USER=admin
export SPIRALOS_ADMIN_PASSWORD='choose-a-long-password'
```

(`SPIRALOS_ADMIN_USER` is optional; it defaults to `admin`.)

### 5. Start SpiralOS

```bash
java -jar target/spiralos-telemetry-0.0.1-SNAPSHOT.jar
```

Wait for the line `Started SpiralosTelemetryApplication`. If Windows Firewall asks about Java, you can click **Cancel**; local access still works.

### 6. Open the dashboard

Go to **http://localhost:8080** and sign in with the username and password from step 4. From another computer on the same network, use `http://<server-ip>:8080`.

To stop SpiralOS, press **Ctrl+C** in the terminal.

---

## Reproducing the test results

| What | Command (Windows: use `.\mvnw.cmd`) | Time | Output |
|---|---|---|---|
| All 82 resilience, security and feature tests | `./mvnw test` | ~30-60 s | Console summary, plus per-class results in `target/surefire-reports/` |
| Real-server stress test (part of the 82) | runs with the command above | ~20 s | `target/stress-proof/server-stress-report.txt` |
| Distributed attack (opt-in, 256 MB heap) | `./mvnw test -Pattack` | ~1-2 min | `target/stress-proof/distributed-attack-report.txt` |
| A single test class | `./mvnw test -Dtest=HomelabFeatureTests` | seconds | Console |

What each test class covers, and the results measured on Windows and on a real Ubuntu server, is in [`RESILIENCE.md`](RESILIENCE.md).

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `'java' is not recognized` / `java: command not found` | Java is not installed or not on the Path. See step 1. |
| `release version 21 not supported` or `invalid target release: 21` | Your Java is older than 21. Install JDK 21 and check `java -version`. |
| `JAVA_HOME is set to an invalid directory` | `JAVA_HOME` must be the JDK folder itself (the one containing `bin`), not `bin`. |
| `SPIRALOS_ADMIN_PASSWORD must be set to a password of at least 12 characters` | Do step 4 in the **same** terminal window, with 12+ characters. |
| `./mvnw: Permission denied` (Linux/macOS) | `chmod +x mvnw`, or run `sh mvnw clean package`. |
| `Port 8080 was already in use` | Stop the other program, or start on another port: `java -jar target/spiralos-telemetry-0.0.1-SNAPSHOT.jar --server.port=8081` |
| Login says "Too many failed attempts" | 5 wrong passwords lock that address out for 15 minutes. Wait, or restart SpiralOS. |
| Windows only: one `ERROR ... HkeyPerformanceDataUtil : Unable to locate English counter names in registry Perflib 009` line | Not a SpiralOS fault. That PC's Windows performance-counter registry is damaged, so the OSHI library falls back to another way of reading process data and everything keeps working. To fix the PC itself, run `lodctr /R` in an **Administrator** Command Prompt. |

---

## Configuration (optional)

Everything needed is already in the repository; nothing has to be edited to run it. These settings in `src/main/resources/application.properties` can be overridden on the command line (e.g. `--spiralos.sampler.interval-ms=5000`):

| Property | Default | Purpose |
|---|---|---|
| `server.port` | `8080` | Web port |
| `spiralos.sampler.interval-ms` | `2000` | How often hardware is sampled |
| `spiralos.settings.path` | `./spiralos-settings.properties` | Where the Settings page saves its values |
| `spiralos.logs.files` | (empty) | Extra log files for the Logs page, `id=path` comma separated, e.g. `pihole=/var/log/pihole/pihole.log` |
| `server.tomcat.max-connections` | `1000` | Connection cap (protects memory under a slowloris attack) |

When serving over HTTPS, also set `SPIRALOS_COOKIE_SECURE=true`.

---

## Project structure

```
src/main/java/spiralos/
  SpiralosTelemetryApplication.java   entry point
  SecurityConfig.java                 login, sessions, CSRF, CSP headers, brute-force lockout
  TelemetrySampler.java               background threads that read OSHI and cache the latest sample
  TelemetryHistory.java               last 60 minutes of samples (fixed-size ring buffer)
  AnomalyDetector.java                threshold + spike rules
  AlertMonitor.java                   raises / clears alerts and records the top processes
  PowerEstimator.java                 watts and monthly cost from CPU load
  DiskMonitor.java / DiskForecast.java   drive usage and time-until-full
  *Controller.java                    the REST API below
  AppSettings.java / SettingsService.java   validated settings, saved to a file
  LogBuffer.java                      in-memory copy of the app log for the Logs page
src/main/resources/static/            HTML, CSS and JavaScript for the dashboard
src/test/java/spiralos/               JUnit resilience, security, stress and feature tests
```

## API

All endpoints require a signed-in session. State-changing requests also need the `X-XSRF-TOKEN` header (the frontend does this automatically).

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/telemetry` | CPU %, memory, network rates, process/thread totals |
| GET | `/api/v1/telemetry/history?minutes=15` | Trend history (1-60 minutes) |
| GET | `/api/v1/telemetry/power` | Power draw and monthly cost estimate |
| GET | `/api/v1/alerts` | Active and recent alerts |
| GET | `/api/v1/disks` | Local drives with usage and forecast |
| GET | `/api/v1/processes?sort=cpu&order=desc&limit=100&q=` | Process list |
| GET | `/api/v1/logs/sources` | Available log sources |
| GET | `/api/v1/logs?source=app&lines=200&level=ALL&q=` | Log lines |
| GET / PUT | `/api/v1/settings` | Read / update settings |
| POST | `/api/v1/settings/reset` | Restore default settings |
| GET | `/api/v1/auth/me` | Current user |

## Notes

- Per-process CPU is measured **between samples** (current load), normalised by logical core count, so it is on the same 0-100 scale as system CPU. PID 0 (System Idle Process) is excluded.
- Network rates exclude the loopback interface.
- Only local drives are read for the Storage section (network mounts are skipped so a dead share can't hang the dashboard).
