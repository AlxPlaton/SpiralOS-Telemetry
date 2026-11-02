package spiralos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import spiralos.SecurityConfig.LoginAttempts;
import spiralos.SecurityConfig.LoginAttempts.Verdict;

/**
 * FAILURE CATEGORY: Security - weaknesses found by DistributedAttackTest and fixed.
 *
 * BEFORE the fix, 60 simultaneous guesses from one IP got 48-60 password checks instead of 5
 * (the "is it locked?" check and the failure counter were separate steps), and a 400-IP botnet
 * got 2,000 guesses in 25 seconds because the only limit was per IP.
 *
 * MITIGATION IMPLEMENTED (SecurityConfig.LoginAttempts):
 *  - attempts are reserved atomically BEFORE the password is checked
 *  - a global per-minute budget for addresses that have never logged in caps a botnet's total
 *    guess rate no matter how many IPs it has; addresses that logged in before are exempt
 */
class LoginThrottleTests {

    @Test
    @DisplayName("60 simultaneous attempts from one IP: exactly 5 are allowed through, never more")
    void simultaneousAttemptsCannotRaceTheLockout() throws Exception {
        LoginAttempts attempts = new LoginAttempts(1_000);
        int threads = 60;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                gate.await();
                if (attempts.tryAcquire("203.0.113.9") == Verdict.ALLOWED) {
                    allowed.incrementAndGet();
                }
                return null;
            });
        }
        gate.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(5, allowed.get());
        assertEquals(Verdict.LOCKED, attempts.tryAcquire("203.0.113.9"));
    }

    @Test
    @DisplayName("Botnet: once the global budget is spent, new IPs are refused no matter how many there are")
    void globalBudgetCapsABotnet() {
        LoginAttempts attempts = new LoginAttempts(30);
        int allowed = 0;
        for (int bot = 0; bot < 1_000; bot++) {
            if (attempts.tryAcquire("198.51." + bot / 250 + "." + bot % 250) == Verdict.ALLOWED) {
                allowed++;
            }
        }
        assertEquals(30, allowed, "1,000 IPs must get no more guesses than the global budget");
        assertEquals(Verdict.BUSY, attempts.tryAcquire("198.51.100.200"));
    }

    @Test
    @DisplayName("During a botnet attack the admin, from an address that logged in before, can still log in")
    void trustedAddressBypassesGlobalBudget() {
        LoginAttempts attempts = new LoginAttempts(30);
        attempts.tryAcquire("192.168.50.10");
        attempts.succeeded("192.168.50.10");
        for (int bot = 0; bot < 100; bot++) {
            attempts.tryAcquire("198.51.100." + bot);
        }
        assertEquals(Verdict.ALLOWED, attempts.tryAcquire("192.168.50.10"));
    }

    @Test
    @DisplayName("A successful login resets that address's attempt count")
    void successResetsCount() {
        LoginAttempts attempts = new LoginAttempts(1_000);
        for (int i = 0; i < 4; i++) {
            attempts.tryAcquire("10.0.0.5");
        }
        attempts.succeeded("10.0.0.5");
        for (int i = 0; i < 5; i++) {
            assertEquals(Verdict.ALLOWED, attempts.tryAcquire("10.0.0.5"), "attempt " + (i + 1) + " after reset");
        }
        assertEquals(Verdict.LOCKED, attempts.tryAcquire("10.0.0.5"));
    }
}
