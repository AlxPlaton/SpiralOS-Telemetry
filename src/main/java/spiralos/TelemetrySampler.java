package spiralos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

/**
 * Samples OSHI on ONE background thread and caches the result. Endpoints just
 * read the cache, so the cost no longer scales with the number of browsers
 * (or attackers) hitting the API, and CPU readings never block a request.
 */
@Component
@EnableScheduling
public class TelemetrySampler {

    private static final Logger log = LoggerFactory.getLogger(TelemetrySampler.class);
    private static final double GB = 1024.0 * 1024.0 * 1024.0;
    private static final double MB = 1024.0 * 1024.0;

    public record ProcRow(int pid, String name, String user, double cpuPercent, long memoryMB,
            int threads, String status) {}

    public record Snapshot(long timestamp, double cpuPercent, int logicalCores, double totalMemoryGB,
            double availableMemoryGB, double networkUpMBs, double networkDownMBs,
            double totalProcessCpuPercent, int processCount, int threadCount, List<ProcRow> processes) {}

    private final HardwareAbstractionLayer hw;
    private final OperatingSystem os;
    private final CentralProcessor cpu;
    private final GlobalMemory mem;
    private final int cores;

    // Only touched by the scheduler thread.
    private long[] prevTicks;
    private long prevRecv;
    private long prevSent;
    private long prevNetTime;
    private Map<Integer, OSProcess> prevProcs = new HashMap<>();

    private volatile Snapshot latest;

    public TelemetrySampler() {
        SystemInfo si = new SystemInfo();
        this.hw = si.getHardware();
        this.os = si.getOperatingSystem();
        this.cpu = hw.getProcessor();
        this.mem = hw.getMemory();
        this.cores = Math.max(1, cpu.getLogicalProcessorCount());
        this.prevTicks = cpu.getSystemCpuLoadTicks();
        this.latest = new Snapshot(System.currentTimeMillis(), 0, cores, r1(mem.getTotal() / GB),
                r1(mem.getAvailable() / GB), 0, 0, 0, 0, 0, List.of());
    }

    public Snapshot latest() {
        return latest;
    }

    @Scheduled(fixedDelayString = "${spiralos.sampler.interval-ms:2000}", initialDelay = 1000)
    void sample() {
        try {
            long now = System.currentTimeMillis();

            // System CPU between two tick snapshots: no 1-second blocking sleep.
            double cpuPercent = clamp(cpu.getSystemCpuLoadBetweenTicks(prevTicks) * 100);
            prevTicks = cpu.getSystemCpuLoadTicks();

            // Network: sum of non-loopback interfaces, turned into a rate.
            long recv = 0;
            long sent = 0;
            for (NetworkIF nif : hw.getNetworkIFs()) {
                if (isLoopback(nif)) {
                    continue;
                }
                nif.updateAttributes();
                recv += nif.getBytesRecv();
                sent += nif.getBytesSent();
            }
            double up = 0;
            double down = 0;
            if (prevNetTime > 0) {
                double secs = Math.max(0.001, (now - prevNetTime) / 1000.0);
                up = Math.max(0, (sent - prevSent) / MB / secs);
                down = Math.max(0, (recv - prevRecv) / MB / secs);
            }
            prevRecv = recv;
            prevSent = sent;
            prevNetTime = now;

            // Processes: CPU is measured BETWEEN samples (current load), not lifetime average.
            List<OSProcess> procs = os.getProcesses();
            Map<Integer, OSProcess> current = new HashMap<>(procs.size() * 2);
            List<ProcRow> rows = new ArrayList<>(procs.size());
            double totalCpu = 0;
            int totalThreads = 0;
            for (OSProcess p : procs) {
                int pid = p.getProcessID();
                current.put(pid, p);
                if (pid == 0) {
                    continue; // System Idle Process is not a real workload
                }
                OSProcess before = prevProcs.get(pid);
                double load = before == null ? 0 : clamp(p.getProcessCpuLoadBetweenTicks(before) * 100 / cores);
                totalCpu += load;
                totalThreads += p.getThreadCount();
                String name = (p.getName() == null || p.getName().isBlank()) ? "(unknown)" : p.getName();
                rows.add(new ProcRow(pid, name, p.getUser() == null ? "" : p.getUser(), r1(load),
                        p.getResidentSetSize() / (1024 * 1024), p.getThreadCount(),
                        p.getState() == null ? "OTHER" : p.getState().name()));
            }
            prevProcs = current;

            latest = new Snapshot(now, r1(cpuPercent), cores, r1(mem.getTotal() / GB),
                    r1(mem.getAvailable() / GB), r2(up), r2(down), r1(Math.min(100, totalCpu)),
                    rows.size(), totalThreads, Collections.unmodifiableList(rows));
        } catch (RuntimeException e) {
            log.error("Telemetry sampling failed: {}", e.toString());
        }
    }

    private static boolean isLoopback(NetworkIF nif) {
        String name = nif.getName() == null ? "" : nif.getName().toLowerCase(Locale.ROOT);
        String display = nif.getDisplayName() == null ? "" : nif.getDisplayName().toLowerCase(Locale.ROOT);
        if (name.equals("lo") || name.equals("lo0") || display.contains("loopback")) {
            return true;
        }
        String[] v4 = nif.getIPv4addr();
        return v4 != null && v4.length > 0 && Arrays.stream(v4).allMatch(a -> a.startsWith("127."));
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }

    private static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
