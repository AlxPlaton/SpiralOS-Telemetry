package spiralos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

import spiralos.TelemetryHistory.Point;

/**
 * Turns recent history into alerts. Two rules, both plain statistics (no
 * machine learning):
 *
 * 1. Threshold: CPU or memory stays at or above the user's warning/critical
 *    threshold for SUSTAIN samples in a row. One busy sample is not an alert,
 *    so the dashboard doesn't flap.
 * 2. Spike: the newest value is far above its own recent baseline (more than
 *    3 standard deviations AND more than a minimum jump), e.g. CPU idling at
 *    10% that suddenly reads 60%. This catches unusual behaviour even when it
 *    is still below the fixed thresholds.
 */
public final class AnomalyDetector {

    public enum Level { WARNING, CRITICAL }

    public record Alert(String key, Level level, String metric, String message, double value) {}

    static final int SUSTAIN = 3;          // samples (6 s at the default interval)
    static final int BASELINE_MIN = 30;    // need a minute of history before judging spikes
    static final int BASELINE_MAX = 150;   // baseline = the 5 minutes before the newest sample
    static final double SIGMAS = 3.0;
    static final double MIN_JUMP_CPU = 25;  // percentage points
    static final double MIN_JUMP_RAM = 15;  // percentage points
    static final double MIN_JUMP_NET = 5;   // MB/s

    private AnomalyDetector() {}

    public static List<Alert> evaluate(List<Point> history, AppSettings s) {
        List<Alert> alerts = new ArrayList<>();
        if (history.isEmpty()) {
            return alerts;
        }
        boolean cpuHigh = threshold(history, Point::cpu, "cpu", "CPU", s.cpuWarnPercent(), s.cpuCritPercent(), alerts);
        boolean ramHigh = threshold(history, Point::ram, "ram", "Memory", s.ramWarnPercent(), s.ramCritPercent(), alerts);
        if (!cpuHigh) {
            spike(history, Point::cpu, "cpu", "CPU", "%", MIN_JUMP_CPU, alerts);
        }
        if (!ramHigh) {
            spike(history, Point::ram, "ram", "Memory", "%", MIN_JUMP_RAM, alerts);
        }
        spike(history, Point::net, "net", "Network", " MB/s", MIN_JUMP_NET, alerts);
        return alerts;
    }

    private static boolean threshold(List<Point> h, ToDoubleFunction<Point> f, String metric, String label,
            int warn, int crit, List<Alert> out) {
        if (h.size() < SUSTAIN) {
            return false;
        }
        double lowest = Double.MAX_VALUE;
        for (Point p : h.subList(h.size() - SUSTAIN, h.size())) {
            lowest = Math.min(lowest, f.applyAsDouble(p));
        }
        double now = f.applyAsDouble(h.get(h.size() - 1));
        if (lowest >= crit) {
            out.add(new Alert(metric + "-threshold", Level.CRITICAL, metric,
                    label + " has been at or above the " + crit + "% critical threshold for " + SUSTAIN
                            + " samples (now " + fmt(now) + "%)", now));
            return true;
        }
        if (lowest >= warn) {
            out.add(new Alert(metric + "-threshold", Level.WARNING, metric,
                    label + " has been at or above the " + warn + "% warning threshold for " + SUSTAIN
                            + " samples (now " + fmt(now) + "%)", now));
            return true;
        }
        return false;
    }

    private static void spike(List<Point> h, ToDoubleFunction<Point> f, String metric, String label, String unit,
            double minJump, List<Alert> out) {
        int n = h.size() - 1; // baseline excludes the newest sample
        if (n < BASELINE_MIN) {
            return;
        }
        List<Point> base = h.subList(Math.max(0, n - BASELINE_MAX), n);
        double sum = 0;
        for (Point p : base) {
            sum += f.applyAsDouble(p);
        }
        double mean = sum / base.size();
        double sq = 0;
        for (Point p : base) {
            double d = f.applyAsDouble(p) - mean;
            sq += d * d;
        }
        double sd = Math.sqrt(sq / base.size());
        double now = f.applyAsDouble(h.get(n));
        double jump = now - mean;
        if (jump >= minJump && jump > SIGMAS * sd) {
            out.add(new Alert(metric + "-spike", Level.WARNING, metric,
                    "Unusual " + label.toLowerCase() + " spike: " + fmt(now) + unit + " vs a recent average of "
                            + fmt(mean) + unit, now));
        }
    }

    private static String fmt(double v) {
        return v >= 10 ? String.valueOf(Math.round(v)) : String.valueOf(Math.round(v * 10) / 10.0);
    }
}
