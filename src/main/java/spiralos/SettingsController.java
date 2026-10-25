package spiralos;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public AppSettings get() {
        return settings.get();
    }

    @PutMapping
    public ResponseEntity<?> update(@RequestBody AppSettings body) {
        try {
            return ResponseEntity.ok(settings.update(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.error("Could not save settings", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not save settings on the server"));
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<?> reset() {
        try {
            return ResponseEntity.ok(settings.reset());
        } catch (IOException e) {
            log.error("Could not reset settings", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not save settings on the server"));
        }
    }
}
