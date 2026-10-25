package spiralos;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private final TelemetrySampler sampler;

    public TelemetryController(TelemetrySampler sampler) {
        this.sampler = sampler;
    }

    @GetMapping
    public Map<String, Object> getTelemetry() {
        TelemetrySampler.Snapshot s = sampler.latest();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", s.timestamp());
        m.put("cpuUsage", s.cpuPercent());
        m.put("logicalCores", s.logicalCores());
        m.put("totalMemoryGB", s.totalMemoryGB());
        m.put("availableMemoryGB", s.availableMemoryGB());
        m.put("networkUpMBs", s.networkUpMBs());
        m.put("networkDownMBs", s.networkDownMBs());
        m.put("totalProcessCpuPercent", s.totalProcessCpuPercent());
        m.put("processCount", s.processCount());
        m.put("threadCount", s.threadCount());
        return m;
    }
}
