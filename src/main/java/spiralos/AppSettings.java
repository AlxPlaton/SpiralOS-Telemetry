package spiralos;

/** Dashboard settings. The ranges here are authoritative; the UI mirrors them. */
public record AppSettings(
        int refreshIntervalMs,
        int cpuWarnPercent,
        int cpuCritPercent,
        int ramWarnPercent,
        int ramCritPercent,
        int dashboardProcessRows,
        int processPageRows,
        int logLines) {

    public static AppSettings defaults() {
        return new AppSettings(2000, 70, 85, 75, 90, 8, 100, 200);
    }

    public void validate() {
        range("refreshIntervalMs", refreshIntervalMs, 1000, 60000);
        range("cpuWarnPercent", cpuWarnPercent, 1, 100);
        range("cpuCritPercent", cpuCritPercent, 1, 100);
        range("ramWarnPercent", ramWarnPercent, 1, 100);
        range("ramCritPercent", ramCritPercent, 1, 100);
        range("dashboardProcessRows", dashboardProcessRows, 3, 20);
        range("processPageRows", processPageRows, 10, 500);
        range("logLines", logLines, 50, 1000);
        if (cpuWarnPercent >= cpuCritPercent) {
            throw new IllegalArgumentException("CPU warning threshold must be lower than the critical threshold");
        }
        if (ramWarnPercent >= ramCritPercent) {
            throw new IllegalArgumentException("Memory warning threshold must be lower than the critical threshold");
        }
    }

    private static void range(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
    }
}
