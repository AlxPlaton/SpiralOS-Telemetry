package spiralos;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only disk usage and time-until-full forecasts. Takes no input. */
@RestController
@RequestMapping("/api/v1/disks")
public class DiskController {

    private final DiskMonitor disks;

    public DiskController(DiskMonitor disks) {
        this.disks = disks;
    }

    @GetMapping
    public Map<String, Object> getDisks() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", System.currentTimeMillis());
        body.put("disks", disks.latest());
        return body;
    }
}
