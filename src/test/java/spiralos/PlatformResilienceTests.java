package spiralos;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FAILURE CATEGORY: Platform / Operating System dependency (file system side).
 *
 * The settings file lives on whatever disk the server runs on. Windows writes
 * CRLF line endings, a Linux admin may hand-edit it with vi, a power cut can
 * leave it half-written, and the configured directory may not exist yet on a
 * fresh install. Any of those used to be able to stop SpiralOS from starting.
 *
 * MITIGATION IMPLEMENTED (SettingsService):
 *  - every value is parsed individually and trimmed; a bad value falls back to its default
 *  - the whole file is validated, and an invalid/corrupt file falls back to defaults
 *  - saves go to a .tmp file and are atomically moved into place, so readers never
 *    see a half-written file (with a non-atomic fallback for file systems that lack it)
 *  - missing parent directories are created on save
 *  - update() is synchronized so concurrent saves cannot interleave
 */
class PlatformResilienceTests {

    @TempDir
    Path dir;

    @Test
    @DisplayName("Missing settings file (fresh install on any OS): starts with defaults, no crash")
    void missingFileUsesDefaults() {
        SettingsService s = new SettingsService(dir.resolve("does-not-exist.properties").toString());
        assertEquals(AppSettings.defaults(), s.get());
    }

    @Test
    @DisplayName("Settings file written on Windows (CRLF) with stray spaces is still read correctly")
    void windowsLineEndingsAndWhitespace() throws Exception {
        Path f = dir.resolve("settings.properties");
        Files.writeString(f, "refreshIntervalMs = 5000  \r\ncpuWarnPercent=60\r\ncpuCritPercent=80\r\n",
                StandardCharsets.ISO_8859_1);
        AppSettings loaded = new SettingsService(f.toString()).get();
        assertEquals(5000, loaded.refreshIntervalMs());
        assertEquals(60, loaded.cpuWarnPercent());
        assertEquals(80, loaded.cpuCritPercent());
        assertEquals(AppSettings.defaults().logLines(), loaded.logLines(), "unspecified keys keep defaults");
    }

    @Test
    @DisplayName("Corrupt / binary garbage settings file (e.g. after a power cut): falls back to defaults")
    void corruptFileFallsBack() throws Exception {
        Path f = dir.resolve("settings.properties");
        Files.write(f, new byte[] { 0, (byte) 0xFF, (byte) 0xFE, 0x5C, 0x75, 0x30, 0x0A, 0x7F });
        assertEquals(AppSettings.defaults(), assertDoesNotThrow(() -> new SettingsService(f.toString()).get()));
    }

    @Test
    @DisplayName("Hand-edited file with impossible values (refresh = 1ms, warn > crit): rejected, defaults used")
    void handEditedInvalidValuesRejected() throws Exception {
        Path f = dir.resolve("settings.properties");
        Files.writeString(f, "refreshIntervalMs=1\ncpuWarnPercent=99\ncpuCritPercent=10\nlogLines=notanumber\n");
        assertEquals(AppSettings.defaults(), new SettingsService(f.toString()).get());
    }

    @Test
    @DisplayName("Configured settings directory does not exist yet: it is created on first save")
    void missingDirectoryIsCreated() throws Exception {
        Path f = dir.resolve("nested/config/dir/settings.properties");
        SettingsService s = new SettingsService(f.toString());
        s.update(new AppSettings(3000, 60, 80, 70, 90, 8, 100, 200));
        assertTrue(Files.isRegularFile(f));
        assertEquals(3000, new SettingsService(f.toString()).get().refreshIntervalMs(), "survives a restart");
    }

    @Test
    @DisplayName("50 simultaneous settings saves: file is never left half-written and reloads cleanly")
    void concurrentSavesNeverCorruptFile() throws Exception {
        Path f = dir.resolve("settings.properties");
        SettingsService s = new SettingsService(f.toString());
        int writers = 50;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch gate = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < writers; i++) {
            int refresh = 1000 + i * 100;
            pool.submit(() -> {
                try {
                    gate.await();
                    s.update(new AppSettings(refresh, 70, 85, 75, 90, 8, 100, 200));
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        gate.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertTrue(failures.isEmpty(), "No concurrent save may fail: " + failures);
        AppSettings reloaded = new SettingsService(f.toString()).get();
        assertEquals(s.get(), reloaded, "what is on disk must match the last value accepted in memory");
        assertTrue(Files.notExists(dir.resolve("settings.properties.tmp")), "no stray temp file left behind");
    }
}
