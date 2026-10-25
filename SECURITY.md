# Security

SpiralOS reads live system data (CPU, memory, network, process list, logs), so it is treated as sensitive from the start. This file describes what is implemented today and what is still planned before public internet exposure.

## Implemented

- **Authentication on everything.** Every page and every `/api/**` endpoint requires login except the login page itself. Unauthenticated API calls get `401`, not a redirect.
- **No credentials in source control.** The admin account comes from the `SPIRALOS_ADMIN_USER` / `SPIRALOS_ADMIN_PASSWORD` environment variables. The app refuses to start if the password is missing or shorter than 12 characters. Passwords are hashed with BCrypt (cost 12) in memory.
- **Brute-force lockout.** 5 failed logins from one address locks that address out for 15 minutes. Successes and failures are written to the application log.
- **Session hardening.** `HttpOnly` + `SameSite=Strict` session cookie, 30-minute idle timeout, cookie-only session tracking, session id rotated on login.
- **CSRF protection** on every state-changing request (settings, logout, login), using a double-submit token cookie.
- **Strict Content-Security-Policy**: no inline scripts, same-origin connections only, no framing. `Referrer-Policy: no-referrer`, `X-Content-Type-Options`, `X-Frame-Options` and cache-control headers come from Spring Security defaults.
- **No same-origin bypass.** CORS is not enabled, so other websites cannot call the API from a visitor's browser.
- **Server-side validation** of every parameter and setting; error responses never include stack traces.
- **No injection from data.** Process names and log lines are rendered with `textContent`, never `innerHTML`.
- **Log sources are operator-defined.** The Logs API only accepts a source id from `application.properties`; clients can never supply a file path.
- **Process command lines are deliberately not exposed** (they often contain tokens and passwords). There is no "kill process" feature.
- **One sampler thread.** OSHI is read on a single background thread and cached, so request volume cannot drive system load.

## Before exposing this to the internet

- [ ] Put it behind a **reverse proxy with HTTPS** (Caddy or nginx); do not forward port 8080 directly.
- [ ] Set `SPIRALOS_COOKIE_SECURE=true` and `server.forward-headers-strategy=native` once TLS is in place.
- [ ] Add **two-factor authentication** (TOTP) or put the app behind a VPN (WireGuard/Tailscale), which is the safest option for a personal tool.
- [ ] Make login lockout persistent and proxy-aware (it is in-memory and keyed on the socket address today).
- [ ] Self-host the fonts instead of loading them from Google Fonts.
- [ ] Run as a dedicated non-root system user via a systemd unit, and keep `oshi-core`, Spring Boot and Spring Security up to date.
- [ ] Support multiple users/roles if more than one person will ever sign in.

## Reporting a concern

This is a personal/learning project. If you spot a security issue, please open an issue on this repo describing it.
