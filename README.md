# SpiralOS

A lightweight, real-time system telemetry dashboard. A Spring Boot backend reads live hardware and process data via [OSHI](https://github.com/oshi/oshi) and serves it as JSON; a static HTML/CSS/JS frontend polls that API and renders it as a NOC-style monitoring panel.

![status](https://img.shields.io/badge/status-active-brightgreen)

## Features

- **Live CPU utilization** — system-wide CPU load, sampled from the OS, with a trailing sparkline and a status flag (`nominal` / `elevated` / `critical`) driven by configurable thresholds.
- **Live memory usage** — used vs. total RAM in GB, with the same sparkline + threshold treatment as CPU.
- **Live network I/O** — upload/download throughput in MB/s, computed server-side as a delta between polls (OSHI only exposes cumulative byte counters, so the backend tracks the previous snapshot and derives a rate).
- **Real active process list** — top 10 processes by CPU usage, pulled fresh from the OS on every poll: PID, name, CPU %, memory, and run status. No mock or placeholder data.
- **Functional navigation** — Dashboard / Processes / Logs / Settings jump to real in-page sections, with a sticky top bar and the active tab tracking scroll position automatically.
- **Live connection status** — the `[ ONLINE ]` badge flips to `[ OFFLINE ]` if a poll to the backend fails, so a dropped connection is visible instead of silent.
- **Logs / Settings** — placeholder panels, reserved and styled, ready to wire up once those endpoints exist.

## Tech stack

| Layer      | Tech                                  |
|------------|----------------------------------------|
| Backend    | Spring Boot, [OSHI](https://github.com/oshi/oshi) (`oshi-core`) |
| Frontend   | Static HTML / CSS / vanilla JS (no build step, no framework) |
| Data       | Polled JSON over `fetch()`, same-origin |

## Project structure

```
src/main/java/spiralos/
  SpiralOSApplication.java
  TelemetryController.java      # REST endpoints, OSHI integration
src/main/resources/static/
  index.html                    # dashboard markup
  style.css                     # NOC/telemetry-themed styling
  script.js                     # polling, rendering, nav logic
```

## Running it

```bash
./mvnw spring-boot:run
```

Then open **http://localhost:8080**. The dashboard starts polling the backend automatically — no separate frontend server needed, since the static files are served by Spring Boot itself.

## API

### `GET /api/v1/telemetry`

Returns current system metrics.

```json
{
  "cpuUsage": 42,
  "totalMemoryGB": 16,
  "availableMemoryGB": 7,
  "networkUpMBs": 0.14,
  "networkDownMBs": 0.52
}
```

### `GET /api/v1/telemetry/processes`

Returns the top 10 processes by CPU usage.

```json
[
  {
    "pid": 22324,
    "name": "eclipse",
    "cpuPercent": 17.6,
    "memoryMB": 1352,
    "status": "RUNNING"
  }
]
```

The frontend polls both endpoints together on a fixed interval (`POLL_MS` in `script.js`, currently 1000ms) and re-renders the metric cards, sparklines, and process table from whatever comes back.

## Known notes / limitations

- **Windows: PID 0 ("System Idle Process")** is deliberately excluded from the process list — it gets credited with CPU time whenever a core is doing nothing, so it reports huge, meaningless load on an idle machine rather than reflecting an actual workload.
- **Process CPU % is normalized by logical core count**, so it reads on roughly the same 0–100 scale as the CPU utilization card, rather than OSHI's raw cumulative value (which can exceed 100% for multi-threaded processes).
- **Network throughput on a near-idle machine can legitimately read close to 0.00 MB/s** — the dashboard shows two decimal places so small amounts of background traffic are still visible instead of rounding away.
- **Polling at 1 second** works but leaves little slack: the backend's CPU sample itself takes ~1 second to measure. If you see growing latency under load, increase `POLL_MS` in `script.js` to 2000ms or higher.
- **Logs and Settings** are UI placeholders only — there's no backend endpoint behind them yet.

## License

MIT — see `LICENSE.txt`.
