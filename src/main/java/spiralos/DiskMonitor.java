package spiralos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import oshi.SystemInfo;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;

/**
 * Disk usage per local drive, plus a short history of used space for the time-until-full
 * forecast. Runs on its own thread: a slow or stuck disk can never freeze the CPU / memory
 * sampler. Only local file systems are read (a dead network mount could block forever), and
 * snap / loop images and RAM disks are skipped because they always look "full".
 */
@Component
public class DiskMonitor {

    private static final Logger log = LoggerFactory.getLogger(DiskMonitor.class);
    private static final double GB = 1e9;

    public record Disk(String mount, String type, double totalGB, double usedGB, double freeGB, double usedPercent,
            DiskForecast.Forecast forecast) {}

    static final int MAX_DISKS = 16;
    static final int HISTORY_POINTS = 720; // 12 hours at the default 60 s interval
    static final long MIN_SIZE_BYTES = 256L * 1024 * 1024;
    static final Set<String> SKIP_TYPES = Set.of("squashfs", "tmpfs", "devtmpfs", "overlay", "iso9660", "udf",
            "ramfs", "efivarfs", "proc", "sysfs", "cgroup", "cgroup2", "autofs", "fuse.snapfuse");

    private final FileSystem fs;
    private final long intervalMs;
    private final Map<String, ArrayDeque<long[]>> history = new LinkedHashMap<>();
    private volatile List<Disk> latest = List.of();
    private ScheduledExecutorService thread;

    public DiskMonitor(@Value("${spiralos.disk.interval-ms:60000}") long intervalMs) {
        this.fs = new SystemInfo().getOperatingSystem().getFileSystem();
        this.intervalMs = intervalMs;
    }

    @PostConstruct
    void start() {
        thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "spiralos-disk-scan");
            t.setDaemon(true);
            return t;
        });
        thread.scheduleWithFixedDelay(this::sample, 2000, intervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        thread.shutdownNow();
    }

    public List<Disk> latest() {
        return latest;
    }

    static boolean worthShowing(String type, long total) {
        return total >= MIN_SIZE_BYTES && !SKIP_TYPES.contains(type == null ? "" : type.toLowerCase(Locale.ROOT));
    }

    void sample() {
        try {
            long now = System.currentTimeMillis();
            List<Disk> out = new ArrayList<>();
            for (OSFileStore st : fs.getFileStores(true)) {
                if (out.size() >= MAX_DISKS) {
                    break;
                }
                long total = st.getTotalSpace();
                if (!worthShowing(st.getType(), total)) {
                    continue;
                }
                long free = st.getUsableSpace();
                long used = Math.max(0, total - free);
                String mount = st.getMount() == null || st.getMount().isBlank() ? st.getName() : st.getMount();
                out.add(record(mount, st.getType(), total, used, free, now));
            }
            latest = List.copyOf(out);
        } catch (RuntimeException e) {
            log.error("Disk scan failed: {}", e.toString());
        }
    }

    // package-private so tests can feed samples without real disks
    synchronized Disk record(String mount, String type, long total, long used, long free, long now) {
        ArrayDeque<long[]> h = history.computeIfAbsent(mount, k -> new ArrayDeque<>());
        if (h.size() >= HISTORY_POINTS) {
            h.removeFirst();
        }
        h.addLast(new long[] { now, used });
        if (history.size() > MAX_DISKS) { // a drive that came and went: forget the oldest
            history.remove(history.keySet().iterator().next());
        }
        DiskForecast.Forecast f = DiskForecast.forecast(new ArrayList<>(h), free);
        return new Disk(mount, type, r1(total / GB), r1(used / GB), r1(free / GB),
                total > 0 ? r1(used * 100.0 / total) : 0, f);
    }

    synchronized int historySize(String mount) {
        ArrayDeque<long[]> h = history.get(mount);
        return h == null ? 0 : h.size();
    }

    private static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
