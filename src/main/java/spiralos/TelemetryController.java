package spiralos;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private final SystemInfo systemInfo = new SystemInfo();

    @GetMapping
    public Map<String, Object> getTelemetry() {
        CentralProcessor processor = systemInfo.getHardware().getProcessor();
        GlobalMemory memory = systemInfo.getHardware().getMemory();

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("cpuUsage", Math.round(processor.getSystemCpuLoad(1000) * 100));
        metrics.put("totalMemoryGB", Math.round((double) memory.getTotal() / (1024 * 1024 * 1024)));
        metrics.put("availableMemoryGB", Math.round((double) memory.getAvailable() / (1024 * 1024 * 1024)));
        
        return metrics;
    }
}