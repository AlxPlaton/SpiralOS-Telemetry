package spiralos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Loads/saves settings as a small .properties file next to the jar. */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final Path file;
    private volatile AppSettings current;

    public SettingsService(@Value("${spiralos.settings.path:./spiralos-settings.properties}") String path) {
        this.file = Path.of(path).toAbsolutePath().normalize();
        this.current = load();
    }

    public AppSettings get() {
        return current;
    }

    public synchronized AppSettings update(AppSettings next) throws IOException {
        next.validate();
        save(next);
        current = next;
        log.info("Settings updated");
        return next;
    }

    public synchronized AppSettings reset() throws IOException {
        return update(AppSettings.defaults());
    }

    private AppSettings load() {
        if (!Files.isRegularFile(file)) {
            return AppSettings.defaults();
        }
        try (InputStream in = Files.newInputStream(file)) {
            Properties p = new Properties();
            p.load(in);
            AppSettings d = AppSettings.defaults();
            AppSettings s = new AppSettings(
                    num(p, "refreshIntervalMs", d.refreshIntervalMs()),
                    num(p, "cpuWarnPercent", d.cpuWarnPercent()),
                    num(p, "cpuCritPercent", d.cpuCritPercent()),
                    num(p, "ramWarnPercent", d.ramWarnPercent()),
                    num(p, "ramCritPercent", d.ramCritPercent()),
                    num(p, "dashboardProcessRows", d.dashboardProcessRows()),
                    num(p, "processPageRows", d.processPageRows()),
                    num(p, "logLines", d.logLines()));
            s.validate();
            return s;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not use settings file {} ({}); falling back to defaults", file, e.getMessage());
            return AppSettings.defaults();
        }
    }

    private void save(AppSettings s) throws IOException {
        Properties p = new Properties();
        p.setProperty("refreshIntervalMs", String.valueOf(s.refreshIntervalMs()));
        p.setProperty("cpuWarnPercent", String.valueOf(s.cpuWarnPercent()));
        p.setProperty("cpuCritPercent", String.valueOf(s.cpuCritPercent()));
        p.setProperty("ramWarnPercent", String.valueOf(s.ramWarnPercent()));
        p.setProperty("ramCritPercent", String.valueOf(s.ramCritPercent()));
        p.setProperty("dashboardProcessRows", String.valueOf(s.dashboardProcessRows()));
        p.setProperty("processPageRows", String.valueOf(s.processPageRows()));
        p.setProperty("logLines", String.valueOf(s.logLines()));

        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "SpiralOS settings");
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int num(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
