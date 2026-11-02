package spiralos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private final TelemetrySampler sampler;
    private final TelemetryHistory history;

    public TelemetryController(TelemetrySampler sampler, TelemetryHistory history) {
        this.sampler = sampler;
        this.history = history;
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

    /** Trend data for the charts: at most the last 60 minutes, already in memory. */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getHistory(@RequestParam(defaultValue = "15") int minutes) {
        if (minutes < 1 || minutes > 60) {
            return ResponseEntity.badRequest().body(Map.of("error", "minutes must be between 1 and 60"));
        }
        List<TelemetryHistory.Point> points = history.recent(minutes);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("minutes", minutes);
        body.put("count", points.size());
        body.put("points", points);
        return ResponseEntity.ok(body);
    }
}
