package spiralos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntToDoubleFunction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import spiralos.AnomalyDetector.Alert;
import spiralos.AnomalyDetector.Level;
import spiralos.TelemetryHistory.Point;

/**
 * INNOVATION (Week 10): historical trends + anomaly detection.
 *
 * Checks the detector raises the right alert for the right reason, stays quiet
 * on normal noise, and that the history and alert lists can never grow without
 * limit (a memory-exhaustion guard, in the spirit of the Week 9 attack tests).
 */
class AnomalyDetectionTests {

    private static final AppSettings DEFAULTS = AppSettings.defaults(); // CPU 70/85, RAM 75/90

    /** n points, 2 s apart, ending now. */
    private static List<Point> series(int n, IntToDoubleFunction cpu, IntToDoubleFunction ram, IntToDoubleFunction net) {
        long start = System.currentTimeMillis() - (n - 1) * 2000L;
        List<Point> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Point(start + i * 2000L, cpu.applyAsDouble(i), ram.applyAsDouble(i), net.applyAsDouble(i)));
        }
        return out;
    }

    /** 200 calm samples, then `tail` CPU readings at the end. */
    private static List<Point> calmThenCpu(double... tail) {
        int calm = 200;
        return series(calm + tail.length, i -> i < calm ? 10 + (i % 3) : tail[i - calm], i -> 50, i -> 0.5);
    }

    private static Alert find(List<Alert> alerts, String key) {
        return alerts.stream().filter(a -> a.key().equals(key)).findFirst().orElse(null);
    }

    @Test
    @DisplayName("Normal, slightly noisy load raises nothing")
    void calmSystemRaisesNothing() {
        assertTrue(AnomalyDetector.evaluate(calmThenCpu(), DEFAULTS).isEmpty());
        assertTrue(AnomalyDetector.evaluate(List.of(), DEFAULTS).isEmpty());
    }

    @Test
    @DisplayName("CPU at or above the critical threshold for 3 samples is CRITICAL (and not double-reported as a spike)")
    void sustainedCriticalCpu() {
        List<Alert> alerts = AnomalyDetector.evaluate(calmThenCpu(95, 96, 97), DEFAULTS);
        Alert a = find(alerts, "cpu-threshold");
        assertNotNull(a);
        assertEquals(Level.CRITICAL, a.level());
        assertNull(find(alerts, "cpu-spike"));
    }

    @Test
    @DisplayName("CPU between warning and critical for 3 samples is a WARNING")
    void sustainedWarningCpu() {
        Alert a = find(AnomalyDetector.evaluate(calmThenCpu(75, 76, 74), DEFAULTS), "cpu-threshold");
        assertNotNull(a);
        assertEquals(Level.WARNING, a.level());
    }

    @Test
    @DisplayName("One busy sample is not a threshold alert (no flapping), but it is flagged as an unusual spike")
    void singleSampleIsSpikeNotThreshold() {
        List<Alert> alerts = AnomalyDetector.evaluate(calmThenCpu(95), DEFAULTS);
        assertNull(find(alerts, "cpu-threshold"));
        assertNotNull(find(alerts, "cpu-spike"));
    }

    @Test
    @DisplayName("A spike still below the fixed thresholds is caught by the statistical rule")
    void spikeBelowThresholds() {
        Alert a = find(AnomalyDetector.evaluate(calmThenCpu(60), DEFAULTS), "cpu-spike");
        assertNotNull(a);
        assertTrue(a.message().contains("60%"), a.message());
    }

    @Test
    @DisplayName("A value that is normal for a noisy machine is not a spike")
    void noisyBaselineIsNotASpike() {
        List<Point> h = series(201, i -> i < 200 ? (i % 2 == 0 ? 10 : 60) : 60, i -> 50, i -> 0.5);
        assertNull(find(AnomalyDetector.evaluate(h, DEFAULTS), "cpu-spike"));
    }

    @Test
    @DisplayName("Spikes are only judged once there is a minute of history")
    void needsBaselineBeforeJudgingSpikes() {
        List<Point> h = series(11, i -> i < 10 ? 10 : 60, i -> 50, i -> 0.5);
        assertTrue(AnomalyDetector.evaluate(h, DEFAULTS).isEmpty());
    }

    @Test
    @DisplayName("The user's own thresholds from Settings are used")
    void userThresholdsAreRespected() {
        AppSettings strict = new AppSettings(2000, 40, 50, 75, 90, 8, 100, 200);
        Alert a = find(AnomalyDetector.evaluate(calmThenCpu(55, 55, 55), strict), "cpu-threshold");
        assertNotNull(a);
        assertEquals(Level.CRITICAL, a.level());
        assertTrue(a.message().contains("50%"), a.message());
    }

    @Test
    @DisplayName("Memory and network are checked too")
    void memoryAndNetwork() {
        List<Point> ram = series(203, i -> 10, i -> i < 200 ? 50 : 93, i -> 0.5);
        assertEquals(Level.CRITICAL, find(AnomalyDetector.evaluate(ram, DEFAULTS), "ram-threshold").level());

        List<Point> net = series(201, i -> 10, i -> 50, i -> i < 200 ? 0.5 : 20);
        assertNotNull(find(AnomalyDetector.evaluate(net, DEFAULTS), "net-spike"));
    }

    @Test
    @DisplayName("Attack: run for days. History never holds more than one hour of points")
    void historyCapacityIsFixed() {
        TelemetryHistory h = new TelemetryHistory();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5000; i++) {
            h.add(new Point(now + i, i, 0, 0));
        }
        assertEquals(TelemetryHistory.CAPACITY, h.size());
        assertEquals(5000 - TelemetryHistory.CAPACITY, h.all().get(0).cpu(), 0.0001, "oldest points are dropped first");
    }

    @Test
    @DisplayName("History returns only the requested time window")
    void historyFiltersByTime() {
        TelemetryHistory h = new TelemetryHistory();
        long now = System.currentTimeMillis();
        h.add(new Point(now - 30 * 60_000L, 1, 0, 0));
        h.add(new Point(now - 60_000L, 2, 0, 0));
        assertEquals(1, h.recent(5).size());
        assertEquals(2, h.recent(60).size());
    }

    @Test
    @DisplayName("Readers (many browsers) never break the writer (the sampler) or see a corrupt list")
    void concurrentReadsDuringWrites() throws Exception {
        TelemetryHistory h = new TelemetryHistory();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        long now = System.currentTimeMillis();
        List<Future<?>> jobs = new ArrayList<>();
        jobs.add(pool.submit(() -> { for (int i = 0; i < 20_000; i++) h.add(new Point(now + i, 1, 1, 1)); }));
        for (int r = 0; r < 4; r++) {
            jobs.add(pool.submit(() -> {
                for (int i = 0; i < 2_000; i++) {
                    List<Point> pts = h.recent(60);
                    assertTrue(pts.size() <= TelemetryHistory.CAPACITY);
                    pts.forEach(p -> assertNotNull(p));
                }
            }));
        }
        for (Future<?> f : jobs) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(TelemetryHistory.CAPACITY, h.size());
    }

    @Test
    @DisplayName("An alert is recorded when raised and marked cleared when the load drops")
    void alertRaisedThenCleared() {
        TelemetryHistory h = new TelemetryHistory();
        AlertMonitor m = new AlertMonitor(h, new SettingsService("target/alert-test-settings.properties"));
        long[] t = { System.currentTimeMillis() };
        for (int i = 0; i < 40; i++) h.add(new Point(t[0]++, 10, 50, 0.5));
        m.check();
        assertTrue(m.active().isEmpty());

        for (int i = 0; i < 3; i++) h.add(new Point(t[0]++, 95, 50, 0.5));
        m.check();
        assertEquals(1, m.active().size());
        assertEquals(1, m.recent().size());
        assertNull(m.recent().get(0).clearedAt());

        for (int i = 0; i < 3; i++) h.add(new Point(t[0]++, 10, 50, 0.5));
        m.check();
        assertTrue(m.active().isEmpty());
        assertEquals(1, m.recent().size(), "clearing updates the same event, it doesn't add one");
        assertNotNull(m.recent().get(0).clearedAt());
    }

    @Test
    @DisplayName("Attack: make the load flap for a long time. The alert log stays capped")
    void alertLogIsCapped() {
        TelemetryHistory h = new TelemetryHistory();
        AlertMonitor m = new AlertMonitor(h, new SettingsService("target/alert-test-settings.properties"));
        long t = System.currentTimeMillis();
        for (int cycle = 0; cycle < 80; cycle++) {
            for (int i = 0; i < 3; i++) h.add(new Point(t++, 95, 50, 0.5));
            m.check();
            for (int i = 0; i < 3; i++) h.add(new Point(t++, 10, 50, 0.5));
            m.check();
        }
        assertTrue(m.recent().size() <= AlertMonitor.MAX_EVENTS, "size " + m.recent().size());
        assertFalse(m.recent().isEmpty());
    }
}
