package spiralos;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the alerts the server has already computed. Takes no input. */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertMonitor monitor;

    public AlertController(AlertMonitor monitor) {
        this.monitor = monitor;
    }

    @GetMapping
    public Map<String, Object> getAlerts() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", System.currentTimeMillis());
        body.put("active", monitor.active());
        body.put("recent", monitor.recent());
        return body;
    }
}
