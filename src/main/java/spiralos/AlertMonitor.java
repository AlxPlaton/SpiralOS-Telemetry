package spiralos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import spiralos.AnomalyDetector.Alert;

/**
 * Runs the anomaly detector after every sample, on the sampler thread, and
 * remembers when each alert was raised and cleared. Every viewer sees the same
 * alerts, and the event list is capped so it can't grow without limit.
 */
@Component
public class AlertMonitor {

    private static final Logger log = LoggerFactory.getLogger(AlertMonitor.class);

    public record Event(String key, String level, String metric, String message, long raisedAt, Long clearedAt) {}

    static final int MAX_EVENTS = 50;

    private final TelemetryHistory history;
    private final SettingsService settings;

    private volatile List<Alert> active = List.of();
    private final Map<String, Long> openSince = new HashMap<>(); // sampler thread only
    private final ArrayDeque<Event> events = new ArrayDeque<>();

    public AlertMonitor(TelemetryHistory history, SettingsService settings) {
        this.history = history;
        this.settings = settings;
    }

    void check() {
        List<Alert> now = AnomalyDetector.evaluate(history.recent(6), settings.get());
        long t = System.currentTimeMillis();
        Map<String, Alert> byKey = new HashMap<>();
        for (Alert a : now) {
            // a warning that escalates to critical is a new event, so the key includes the level
            String key = a.key() + ":" + a.level();
            byKey.put(key, a);
            if (!openSince.containsKey(key)) {
                openSince.put(key, t);
                addEvent(new Event(key, a.level().name(), a.metric(), a.message(), t, null));
                log.warn("Alert raised: {}", a.message());
            }
        }
        for (String key : new ArrayList<>(openSince.keySet())) {
            if (!byKey.containsKey(key)) {
                long since = openSince.remove(key);
                closeEvent(key, since, t);
                log.info("Alert cleared: {}", key);
            }
        }
        active = Collections.unmodifiableList(now);
    }

    public List<Alert> active() {
        return active;
    }

    /** Newest first. */
    public List<Event> recent() {
        synchronized (events) {
            List<Event> out = new ArrayList<>(events);
            Collections.reverse(out);
            return out;
        }
    }

    private void addEvent(Event e) {
        synchronized (events) {
            if (events.size() >= MAX_EVENTS) {
                events.removeFirst();
            }
            events.addLast(e);
        }
    }

    private void closeEvent(String key, long raisedAt, long clearedAt) {
        synchronized (events) {
            List<Event> copy = new ArrayList<>(events);
            for (int i = copy.size() - 1; i >= 0; i--) {
                Event e = copy.get(i);
                if (e.key().equals(key) && e.raisedAt() == raisedAt && e.clearedAt() == null) {
                    copy.set(i, new Event(e.key(), e.level(), e.metric(), e.message(), e.raisedAt(), clearedAt));
                    events.clear();
                    events.addAll(copy);
                    return;
                }
            }
        }
    }
}
