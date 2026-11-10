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
        int logLines,
        int idleWatts,
        int maxWatts,
        int centsPerKwh) {

    public static final int DEFAULT_IDLE_WATTS = 30;
    public static final int DEFAULT_MAX_WATTS = 120;
    public static final int DEFAULT_CENTS_PER_KWH = 15;

    /** The original eight settings, with the power-estimate settings at their defaults. */
    public AppSettings(int refreshIntervalMs, int cpuWarnPercent, int cpuCritPercent, int ramWarnPercent,
            int ramCritPercent, int dashboardProcessRows, int processPageRows, int logLines) {
        this(refreshIntervalMs, cpuWarnPercent, cpuCritPercent, ramWarnPercent, ramCritPercent,
                dashboardProcessRows, processPageRows, logLines,
                DEFAULT_IDLE_WATTS, DEFAULT_MAX_WATTS, DEFAULT_CENTS_PER_KWH);
    }

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
        range("idleWatts", idleWatts, 1, 2000);
        range("maxWatts", maxWatts, 1, 5000);
        range("centsPerKwh", centsPerKwh, 1, 500);
        if (cpuWarnPercent >= cpuCritPercent) {
            throw new IllegalArgumentException("CPU warning threshold must be lower than the critical threshold");
        }
        if (ramWarnPercent >= ramCritPercent) {
            throw new IllegalArgumentException("Memory warning threshold must be lower than the critical threshold");
        }
        if (idleWatts >= maxWatts) {
            throw new IllegalArgumentException("Idle power must be lower than full-load power");
        }
    }

    private static void range(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
    }
}
