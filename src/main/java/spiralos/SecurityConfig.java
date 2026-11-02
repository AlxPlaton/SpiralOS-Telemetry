package spiralos;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Everything is behind login. Session cookie (HttpOnly, SameSite=Strict), CSRF
 * protection for every state-changing request, strict CSP, and a per-IP login
 * lockout. Credentials come from the environment, never from source control.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String CSP = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; "
            + "img-src 'self' data:; connect-src 'self'; form-action 'self'; "
            + "frame-ancestors 'none'; base-uri 'self'; object-src 'none'";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService userDetailsService(PasswordEncoder encoder,
            @Value("${SPIRALOS_ADMIN_USER:admin}") String username,
            @Value("${SPIRALOS_ADMIN_PASSWORD:}") String password) {
        if (password.length() < 12) {
            throw new IllegalStateException(
                    "SPIRALOS_ADMIN_PASSWORD must be set to a password of at least 12 characters. "
                            + "SpiralOS refuses to start without one.");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password(encoder.encode(password)).roles("ADMIN").build());
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
            @Value("${spiralos.login.global-max-per-minute:30}") int globalLoginsPerMinute) throws Exception {
        LoginAttempts attempts = new LoginAttempts(globalLoginsPerMinute);

        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(cookie -> cookie.sameSite("Strict"));

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepo)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .addFilterBefore(new LoginThrottleFilter(attempts), UsernamePasswordAuthenticationFilter.class)
            // Login always lands on "/", so remembering the page an anonymous visitor asked for is
            // pointless - and doing so created a server session per anonymous request (memory DoS).
            .requestCache(cache -> cache.requestCache(new NullRequestCache()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login.html", "/login.js", "/style.css", "/favicon.ico", "/error").permitAll()
                .requestMatchers(HttpMethod.POST, "/login").permitAll()
                .anyRequest().hasRole("ADMIN"))
            .formLogin(form -> form
                .loginPage("/login.html")
                .loginProcessingUrl("/login")
                .successHandler((req, res, auth) -> {
                    attempts.succeeded(req.getRemoteAddr());
                    log.info("Successful login from {}", req.getRemoteAddr());
                    res.sendRedirect("/");
                })
                .failureHandler((req, res, ex) -> {
                    log.warn("Failed login attempt from {}", req.getRemoteAddr());
                    res.sendRedirect("/login.html?error");
                }))
            .logout(logout -> logout
                .logoutUrl("/logout")
                .invalidateHttpSession(true)
                .deleteCookies("SPIRALOS_SESSION", "XSRF-TOKEN")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_OK)))
            .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, e) -> {
                if (req.getRequestURI().startsWith("/api/")) {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                } else {
                    res.sendRedirect("/login.html");
                }
            }))
            .headers(h -> h
                .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)));

        return http.build();
    }

    /** Forces the XSRF-TOKEN cookie onto every response so the static pages can read it. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute("_csrf");
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }

    /** Reserves a login attempt before the password is checked; rejects it if the address or the server is over budget. */
    static final class LoginThrottleFilter extends OncePerRequestFilter {
        private final LoginAttempts attempts;

        LoginThrottleFilter(LoginAttempts attempts) {
            this.attempts = attempts;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            if ("POST".equalsIgnoreCase(request.getMethod()) && "/login".equals(request.getRequestURI())) {
                String ip = request.getRemoteAddr();
                LoginAttempts.Verdict verdict = attempts.tryAcquire(ip);
                if (verdict == LoginAttempts.Verdict.LOCKED) {
                    log.warn("Rejected login from locked-out address {}", ip);
                    response.sendRedirect("/login.html?locked");
                    return;
                }
                if (verdict == LoginAttempts.Verdict.BUSY) {
                    log.warn("Rejected login from {}: global login rate limit reached", ip);
                    response.sendRedirect("/login.html?busy");
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * Login throttling. Attempts are counted when they START, not when they fail, so guesses
     * sent simultaneously cannot all pass the check before any failure is recorded.
     *  - per address: 5 attempts, then locked out for 15 minutes (a success resets it)
     *  - globally: at most N attempts per minute from addresses that have never logged in
     *    successfully, so a botnet's total guess rate (and BCrypt CPU cost) no longer grows
     *    with the number of IPs it controls. Addresses that have logged in before are exempt,
     *    so the admin can still get in during an attack.
     * In-memory: resets on restart.
     */
    static final class LoginAttempts {
        enum Verdict { ALLOWED, LOCKED, BUSY }

        private static final int MAX_ATTEMPTS = 5;
        private static final long LOCK_MS = 15 * 60 * 1000L;
        private static final long GLOBAL_WINDOW_MS = 60 * 1000L;
        private static final int MAX_TRACKED = 10_000;
        private static final int MAX_TRUSTED = 1_000;

        private record State(int attempts, long lastAttempt, long lockedUntil) {}

        private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
        private final Set<String> trusted = ConcurrentHashMap.newKeySet();
        private final int globalMaxPerMinute;
        private long windowStart;
        private int windowCount;

        LoginAttempts(int globalMaxPerMinute) {
            this.globalMaxPerMinute = globalMaxPerMinute;
        }

        Verdict tryAcquire(String ip) {
            long now = System.currentTimeMillis();
            State current = states.get(ip);
            if (current != null && current.lockedUntil() > now) {
                return Verdict.LOCKED;
            }
            if (!trusted.contains(ip) && !takeGlobal(now)) {
                return Verdict.BUSY;
            }
            boolean[] allowed = { false };
            states.compute(ip, (k, s) -> {
                if (s != null && s.lockedUntil() > now) {
                    return s; // another thread locked it between the check above and here
                }
                int n = (s == null || now - s.lastAttempt() > LOCK_MS) ? 1 : s.attempts() + 1;
                allowed[0] = true;
                return new State(n, now, n >= MAX_ATTEMPTS ? now + LOCK_MS : 0);
            });
            if (states.size() > MAX_TRACKED) {
                states.entrySet().removeIf(e -> now - e.getValue().lastAttempt() > LOCK_MS);
            }
            if (!allowed[0]) {
                if (!trusted.contains(ip)) {
                    releaseGlobal(); // a locked-out attempt never reaches BCrypt, so it should not use the budget
                }
                return Verdict.LOCKED;
            }
            return Verdict.ALLOWED;
        }

        private synchronized void releaseGlobal() {
            if (windowCount > 0) {
                windowCount--;
            }
        }

        private synchronized boolean takeGlobal(long now) {
            if (now - windowStart >= GLOBAL_WINDOW_MS) {
                windowStart = now;
                windowCount = 0;
            }
            if (windowCount >= globalMaxPerMinute) {
                return false;
            }
            windowCount++;
            return true;
        }

        void succeeded(String ip) {
            states.remove(ip);
            if (trusted.size() < MAX_TRUSTED) {
                trusted.add(ip);
            }
        }
    }
}
