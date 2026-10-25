package spiralos;

import java.io.IOException;
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
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        LoginAttempts attempts = new LoginAttempts();

        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(cookie -> cookie.sameSite("Strict"));

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepo)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .addFilterBefore(new LoginThrottleFilter(attempts), UsernamePasswordAuthenticationFilter.class)
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
                    attempts.failed(req.getRemoteAddr());
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

    /** Rejects login POSTs from addresses that are currently locked out. */
    static final class LoginThrottleFilter extends OncePerRequestFilter {
        private final LoginAttempts attempts;

        LoginThrottleFilter(LoginAttempts attempts) {
            this.attempts = attempts;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            if ("POST".equalsIgnoreCase(request.getMethod()) && "/login".equals(request.getRequestURI())
                    && attempts.isBlocked(request.getRemoteAddr())) {
                log.warn("Rejected login from locked-out address {}", request.getRemoteAddr());
                response.sendRedirect("/login.html?locked");
                return;
            }
            chain.doFilter(request, response);
        }
    }

    /** 5 failures from one address locks it out for 15 minutes. In-memory: resets on restart. */
    static final class LoginAttempts {
        private static final int MAX_FAILURES = 5;
        private static final long LOCK_MS = 15 * 60 * 1000L;

        private record State(int failures, long lastFailure, long lockedUntil) {}

        private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

        boolean isBlocked(String ip) {
            State s = states.get(ip);
            return s != null && s.lockedUntil() > System.currentTimeMillis();
        }

        void failed(String ip) {
            long now = System.currentTimeMillis();
            states.compute(ip, (k, s) -> {
                int failures = (s == null || now - s.lastFailure() > LOCK_MS) ? 1 : s.failures() + 1;
                return new State(failures, now, failures >= MAX_FAILURES ? now + LOCK_MS : 0);
            });
            if (states.size() > 10_000) {
                states.entrySet().removeIf(e -> now - e.getValue().lastFailure() > LOCK_MS);
            }
        }

        void succeeded(String ip) {
            states.remove(ip);
        }
    }
}
