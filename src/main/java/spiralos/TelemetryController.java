package spiralos;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private final SystemInfo systemInfo = new SystemInfo();

    // OSHI only exposes cumulative byte counters per interface, not a rate.
    // We keep the previous snapshot + timestamp here (this controller is a
    // Spring singleton, so this is safe as simple shared state for one
    // instance) and compute MB/s as the delta between polls.
    private final AtomicReference<NetworkSnapshot> lastNetworkSnapshot = new AtomicReference<>();

    @GetMapping
    public Map<String, Object> getTelemetry() {
        CentralProcessor processor = systemInfo.getHardware().getProcessor();
        GlobalMemory memory = systemInfo.getHardware().getMemory();

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("cpuUsage", Math.round(processor.getSystemCpuLoad(1000) * 100));
        metrics.put("totalMemoryGB", Math.round((double) memory.getTotal() / (1024 * 1024 * 1024)));
        metrics.put("availableMemoryGB", Math.round((double) memory.getAvailable() / (1024 * 1024 * 1024)));

        double[] networkRatesMBs = getNetworkRatesMBs();
        // round2, not round1, here: round1's 0.1 MB/s (100 KB/s) steps swallow
        // normal idle-machine background traffic entirely, making the card
        // look stuck at 0.0 even when data is genuinely moving.
        metrics.put("networkUpMBs", round2(networkRatesMBs[0]));
        metrics.put("networkDownMBs", round2(networkRatesMBs[1]));

        return metrics;
    }

    @GetMapping("/processes")
    public List<Map<String, Object>> getProcesses() {
        OperatingSystem os = systemInfo.getOperatingSystem();
        int logicalCores = Math.max(1, systemInfo.getHardware().getProcessor().getLogicalProcessorCount());

        // NOTE: OSHI's OperatingSystem.getProcesses(...) overload set has
        // changed across major versions (5.x vs 6.x). Calling the no-arg
        // getProcesses() and sorting/limiting here ourselves is the most
        // version-stable approach. If your oshi-core version exposes a
        // different method (or this one is deprecated/missing), swap this
        // line for whatever your IDE's autocomplete offers and the rest of
        // the method can stay the same.
        List<OSProcess> allProcesses = os.getProcesses();

        List<OSProcess> topProcesses = allProcesses.stream()
                // PID 0 ("System Idle Process" on Windows / equivalent on
                // other OSes) isn't a real workload — it's credited with CPU
                // time whenever a core is doing nothing, so on a mostly-idle
                // machine it racks up huge, meaningless cumulative load.
                .filter(p -> p.getProcessID() != 0)
                .sorted(Comparator.comparingDouble(OSProcess::getProcessCpuLoadCumulative).reversed())
                .limit(10)
                .toList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (OSProcess p : topProcesses) {
            Map<String, Object> row = new HashMap<>();
            row.put("pid", p.getProcessID());
            row.put("name", p.getName());
            // getProcessCpuLoadCumulative() is total CPU-seconds consumed
            // divided by process uptime — NOT pre-divided by core count. A
            // process that's kept several cores busy over its lifetime can
            // read well over 100%. Dividing by logicalCores here puts it on
            // the same 0-100ish scale as the CPU utilization card above.
            double normalizedPercent = (p.getProcessCpuLoadCumulative() * 100) / logicalCores;
            row.put("cpuPercent", round1(normalizedPercent));
            row.put("memoryMB", p.getResidentSetSize() / (1024 * 1024));
            row.put("status", p.getState().name());
            result.add(row);
        }
        return result;
    }

    private double[] getNetworkRatesMBs() {
        List<NetworkIF> interfaces = systemInfo.getHardware().getNetworkIFs();

        long totalBytesRecv = 0;
        long totalBytesSent = 0;
        for (NetworkIF nif : interfaces) {
            nif.updateAttributes();
            totalBytesRecv += nif.getBytesRecv();
            totalBytesSent += nif.getBytesSent();
        }

        long now = System.currentTimeMillis();
        NetworkSnapshot previous = lastNetworkSnapshot.getAndSet(
                new NetworkSnapshot(totalBytesRecv, totalBytesSent, now));

        // First call ever (or right after a restart) — no prior snapshot to
        // diff against, so report zero rather than a huge bogus spike.
        if (previous == null) {
            return new double[] { 0, 0 };
        }

        double elapsedSeconds = Math.max(0.001, (now - previous.timestampMs) / 1000.0);
        double downMBs = (totalBytesRecv - previous.bytesRecv) / (1024.0 * 1024.0) / elapsedSeconds;
        double upMBs = (totalBytesSent - previous.bytesSent) / (1024.0 * 1024.0) / elapsedSeconds;

        // Counters can appear to go "backwards" if an interface resets;
        // clamp to zero rather than show a negative rate.
        return new double[] { Math.max(0, upMBs), Math.max(0, downMBs) };
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static final class NetworkSnapshot {
        final long bytesRecv;
        final long bytesSent;
        final long timestampMs;

        NetworkSnapshot(long bytesRecv, long bytesSent, long timestampMs) {
            this.bytesRecv = bytesRecv;
            this.bytesSent = bytesSent;
            this.timestampMs = timestampMs;
        }
    }
}
