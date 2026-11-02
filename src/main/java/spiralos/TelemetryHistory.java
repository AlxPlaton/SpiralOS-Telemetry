package spiralos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * The last hour of samples, kept in memory for the trend charts and the
 * anomaly detector. Fixed capacity: no matter how long the server runs or how
 * many clients ask for history, this never holds more than CAPACITY points.
 */
@Component
public class TelemetryHistory {

    /** One sample: time, CPU %, memory used %, network total MB/s. */
    public record Point(long t, double cpu, double ram, double net) {}

    /** 60 minutes at the default 2-second sampling interval. */
    public static final int CAPACITY = 1800;

    private final ArrayDeque<Point> points = new ArrayDeque<>(CAPACITY);

    public Point record(TelemetrySampler.Snapshot s) {
        double total = s.totalMemoryGB();
        double ram = total > 0 ? Math.max(0, Math.min(100, (total - s.availableMemoryGB()) / total * 100)) : 0;
        Point p = new Point(s.timestamp(), s.cpuPercent(), Math.round(ram * 10) / 10.0,
                Math.round((s.networkUpMBs() + s.networkDownMBs()) * 100) / 100.0);
        add(p);
        return p;
    }

    // package-private so tests can feed points directly
    void add(Point p) {
        synchronized (points) {
            if (points.size() >= CAPACITY) {
                points.removeFirst();
            }
            points.addLast(p);
        }
    }

    /** All points from the last {@code minutes}, oldest first. */
    public List<Point> recent(int minutes) {
        long cutoff = System.currentTimeMillis() - minutes * 60_000L;
        synchronized (points) {
            List<Point> out = new ArrayList<>(Math.min(points.size(), minutes * 30 + 1));
            for (Point p : points) {
                if (p.t() >= cutoff) {
                    out.add(p);
                }
            }
            return out;
        }
    }

    public List<Point> all() {
        synchronized (points) {
            return new ArrayList<>(points);
        }
    }

    public int size() {
        synchronized (points) {
            return points.size();
        }
    }
}
