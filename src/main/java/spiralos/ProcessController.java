package spiralos;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import spiralos.TelemetrySampler.ProcRow;

@RestController
@RequestMapping("/api/v1/processes")
public class ProcessController {

    private final TelemetrySampler sampler;

    public ProcessController(TelemetrySampler sampler) {
        this.sampler = sampler;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "cpu") String sort,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "") String q) {

        if (limit < 1 || limit > 500) {
            return bad("limit must be between 1 and 500");
        }
        if (q.length() > 64) {
            return bad("search text is too long");
        }
        if (!order.equals("asc") && !order.equals("desc")) {
            return bad("order must be asc or desc");
        }

        Comparator<ProcRow> cmp = switch (sort) {
            case "cpu" -> Comparator.comparingDouble(ProcRow::cpuPercent);
            case "memory" -> Comparator.comparingLong(ProcRow::memoryMB);
            case "name" -> Comparator.comparing((ProcRow r) -> r.name().toLowerCase(Locale.ROOT));
            case "pid" -> Comparator.comparingInt(ProcRow::pid);
            case "threads" -> Comparator.comparingInt(ProcRow::threads);
            default -> null;
        };
        if (cmp == null) {
            return bad("sort must be one of cpu, memory, name, pid, threads");
        }
        if (order.equals("desc")) {
            cmp = cmp.reversed();
        }

        TelemetrySampler.Snapshot s = sampler.latest();
        String needle = q.toLowerCase(Locale.ROOT).trim();

        List<ProcRow> matched = s.processes().stream()
                .filter(p -> needle.isEmpty()
                        || p.name().toLowerCase(Locale.ROOT).contains(needle)
                        || p.user().toLowerCase(Locale.ROOT).contains(needle)
                        || String.valueOf(p.pid()).contains(needle))
                .sorted(cmp)
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", s.timestamp());
        body.put("systemCpuPercent", s.cpuPercent());
        body.put("totalCpuPercent", s.totalProcessCpuPercent());
        body.put("processCount", s.processCount());
        body.put("threadCount", s.threadCount());
        body.put("matched", matched.size());
        body.put("processes", matched.subList(0, Math.min(limit, matched.size())));
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
