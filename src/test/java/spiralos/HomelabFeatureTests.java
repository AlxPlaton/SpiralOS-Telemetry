package spiralos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import spiralos.DiskForecast.Forecast;
import spiralos.DiskForecast.Status;
import spiralos.TelemetryHistory.Point;
import spiralos.TelemetrySampler.ProcRow;

/**
 * Home-lab features: alert culprits, the power / cost estimate, and disk time-until-full.
 * Each one is checked for the right answer, for staying quiet when it should, and for
 * bounded memory, in the same spirit as the Week 9 attack tests.
 */
class HomelabFeatureTests {

    private static ProcRow proc(int pid, String name, double cpu, long memMb) {
        return new ProcRow(pid, name, "user", cpu, memMb, 4, "RUNNING");
    }

    private static final List<ProcRow> PROCS = List.of(
            proc(1, "idle-daemon", 0.1, 20), proc(2, "java", 61.5, 900), proc(3, "plex", 22.0, 3200),
            proc(4, "sshd", 0.2, 8), proc(5, "backup", 9.4, 150), proc(6, "chrome", 4.0, 2500));

    // ---------------------------------------------------------------- alert culprits

    @Test
    @DisplayName("CPU alert names the top 3 CPU users, highest first, ignoring idle processes")
    void cpuCulprits() {
        assertEquals(List.of("java (pid 2) 61.5%", "plex (pid 3) 22.0%", "backup (pid 5) 9.4%"),
                AlertMonitor.culprits("cpu", PROCS));
    }

    @Test
    @DisplayName("Memory alert names the top 3 memory users; network has no per-process data")
    void memoryCulpritsAndNone() {
        List<String> ram = AlertMonitor.culprits("ram", PROCS);
        assertEquals(3, ram.size());
        assertTrue(ram.get(0).startsWith("plex (pid 3)"), ram.toString());
        assertTrue(AlertMonitor.culprits("net", PROCS).isEmpty());
        assertTrue(AlertMonitor.culprits("cpu", null).isEmpty());
    }

    @Test
    @DisplayName("Culprits are captured when the alert is raised and kept after it clears")
    void culpritsSurviveClearing() {
        TelemetryHistory h = new TelemetryHistory();
        AlertMonitor m = new AlertMonitor(h, new SettingsService("target/homelab-test-settings.properties"));
        long t = System.currentTimeMillis();
        for (int i = 0; i < 40; i++) h.add(new Point(t++, 10, 50, 0.5));
        for (int i = 0; i < 3; i++) h.add(new Point(t++, 95, 50, 0.5));
        m.check(PROCS);
        assertEquals("java (pid 2) 61.5%", m.recent().get(0).culprits().get(0));

        for (int i = 0; i < 3; i++) h.add(new Point(t++, 10, 50, 0.5));
        m.check(List.of()); // load is gone; the snapshot taken at raise time must remain
        assertNotNull(m.recent().get(0).clearedAt());
        assertEquals(3, m.recent().get(0).culprits().size());
    }

    // ---------------------------------------------------------------- power estimate

    @Test
    @DisplayName("Power is idle watts at 0% CPU, full-load watts at 100%, linear in between")
    void powerModel() {
        AppSettings s = AppSettings.defaults(); // 30 W idle, 120 W max, 15 cents/kWh
        assertEquals(30, PowerEstimator.watts(0, s), 0.001);
        assertEquals(120, PowerEstimator.watts(100, s), 0.001);
        assertEquals(75, PowerEstimator.watts(50, s), 0.001);
        assertEquals(120, PowerEstimator.watts(250, s), 0.001, "readings above 100% are clamped");
    }

    @Test
    @DisplayName("Monthly cost: a steady 100 W at 15 cents/kWh is about 73 kWh and $10.96")
    void powerCost() {
        AppSettings s = new AppSettings(2000, 70, 85, 75, 90, 8, 100, 200, 100, 200, 15);
        long t = System.currentTimeMillis();
        List<Point> idleHour = new ArrayList<>();
        for (int i = 0; i < 1800; i++) idleHour.add(new Point(t + i * 2000L, 0, 50, 0));
        PowerEstimator.Estimate e = PowerEstimator.estimate(idleHour, 0, s);
        assertEquals(100, e.wattsAvg(), 0.001);
        assertEquals(73.1, e.kwhPerMonth(), 0.001);
        assertEquals(10.96, e.costPerMonth(), 0.001);
        assertEquals(60, e.minutes(), 0.1);
    }

    @Test
    @DisplayName("Attack: invalid power settings (idle above full load, absurd watts) are rejected")
    void powerSettingsValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> new AppSettings(2000, 70, 85, 75, 90, 8, 100, 200, 150, 100, 15).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new AppSettings(2000, 70, 85, 75, 90, 8, 100, 200, 30, 999_999, 15).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new AppSettings(2000, 70, 85, 75, 90, 8, 100, 200, 30, 120, 0).validate());
    }

    @Test
    @DisplayName("An older settings file without the power keys still loads, with power defaults")
    void oldSettingsFileStillLoads(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("old.properties");
        Files.writeString(f, "refreshIntervalMs=3000\ncpuWarnPercent=60\ncpuCritPercent=80\n");
        AppSettings s = new SettingsService(f.toString()).get();
        assertEquals(3000, s.refreshIntervalMs());
        assertEquals(AppSettings.DEFAULT_IDLE_WATTS, s.idleWatts());
        assertEquals(AppSettings.DEFAULT_CENTS_PER_KWH, s.centsPerKwh());
    }

    // ---------------------------------------------------------------- disk forecast

    /** n samples one minute apart, used bytes from the function. */
    private static List<long[]> disk(int n, java.util.function.IntToLongFunction used) {
        long t = System.currentTimeMillis() - n * 60_000L;
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new long[] { t + i * 60_000L, used.applyAsLong(i) });
        return out;
    }

    @Test
    @DisplayName("No forecast until there is half an hour of data")
    void forecastNeedsData() {
        assertEquals(Status.COLLECTING, DiskForecast.forecast(disk(20, i -> i * 1_000_000_000L), 1).status());
    }

    @Test
    @DisplayName("A disk growing 1 GB an hour with 100 GB free is full in about 100 hours")
    void forecastFilling() {
        Forecast f = DiskForecast.forecast(disk(120, i -> 400_000_000_000L + i * (1_000_000_000L / 60)), 100_000_000_000L);
        assertEquals(Status.FILLING, f.status());
        assertEquals(100, f.hoursToFull(), 1.0);
        assertEquals(24, f.gbPerDay(), 0.5);
    }

    @Test
    @DisplayName("Normal churn (a few MB up and down) is stable, not a scary date; shrinking is stable too")
    void forecastStable() {
        Forecast churn = DiskForecast.forecast(disk(120, i -> 400_000_000_000L + (i % 2) * 5_000_000L), 1L << 40);
        assertEquals(Status.STABLE, churn.status());
        assertNull(churn.hoursToFull());
        Forecast shrinking = DiskForecast.forecast(disk(120, i -> 400_000_000_000L - i * 50_000_000L), 1L << 40);
        assertEquals(Status.STABLE, shrinking.status());
        assertTrue(shrinking.gbPerDay() < 0);
    }

    @Test
    @DisplayName("Snap images, RAM disks and tiny volumes are not shown as drives")
    void diskFilter() {
        assertFalse(DiskMonitor.worthShowing("squashfs", 1L << 34));
        assertFalse(DiskMonitor.worthShowing("tmpfs", 1L << 34));
        assertFalse(DiskMonitor.worthShowing("ext4", 1024));
        assertTrue(DiskMonitor.worthShowing("ext4", 1L << 34));
        assertTrue(DiskMonitor.worthShowing("NTFS", 1L << 34));
    }

    @Test
    @DisplayName("Attack: run for weeks. Each drive keeps at most 12 hours of history")
    void diskHistoryCapped() {
        DiskMonitor m = new DiskMonitor(60_000);
        long t = System.currentTimeMillis();
        for (int i = 0; i < 5000; i++) m.record("/data", "ext4", 1L << 40, i, (1L << 40) - i, t + i);
        assertEquals(DiskMonitor.HISTORY_POINTS, m.historySize("/data"));
    }
}
