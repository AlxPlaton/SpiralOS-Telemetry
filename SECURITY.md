# Security

## Current status: LAN-only, pre-hardening

SpiralOS is currently developed and deployed for **local network use only**. It is **not** intended to be exposed to the public internet in its current state, and doing so is not recommended. Specifically, as of now:

- There is **no authentication** — anyone who can reach the dashboard's port can view full system telemetry and the live process list.
- There is **no HTTPS/TLS** — traffic between browser and server is unencrypted.
- There is **no rate limiting** on the API endpoints.
- The server-hosting firewall currently scopes access to the local subnet (`ufw allow from <LAN range> to any port ...`), which is the only thing currently preventing outside access — not anything in the application itself.

This is an intentional, staged approach: get core functionality working and correct first, harden before widening exposure.

## Planned before any internet-facing deployment

- [ ] **Authentication** on both the dashboard and the API endpoints (e.g. Spring Security with a login, or token-based auth for the REST endpoints).
- [ ] **HTTPS/TLS**, either via Spring Boot's built-in SSL config or terminated at a reverse proxy (nginx/Caddy) in front of the app.
- [ ] **Reverse proxy in front of the embedded Tomcat server**, rather than exposing port 8080 directly.
- [ ] **Rate limiting** on `/api/v1/telemetry` and `/api/v1/telemetry/processes` to prevent abuse/scraping.
- [ ] **Run as a dedicated non-root system user** via a systemd service, rather than an interactive shell session.
- [ ] **Restrict CORS** explicitly rather than relying on same-origin defaults, once this is no longer a single-origin static+API app.
- [ ] **Audit what the process list actually exposes** — process names/args can leak information about what's running on a box; worth deciding what's safe to show once this isn't LAN-only.
- [ ] **Keep `oshi-core` and Spring Boot dependencies current** — this app reads raw OS/hardware data, so dependency hygiene matters more than average.

## Reporting a concern

This is a personal/learning project rather than a maintained public package, but if you spot a security issue, please open an issue on this repo describing it rather than relying on it being obvious from the code.
