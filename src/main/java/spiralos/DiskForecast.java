package spiralos;

import java.util.List;

/**
 * "When will this disk be full?" from a straight-line fit (least squares) of used space over
 * time. Deliberately cautious: no forecast until there is half an hour of data, and growth
 * slower than 50 MB a day counts as stable so normal log churn doesn't produce scary dates.
 */
public final class DiskForecast {

    public enum Status { COLLECTING, STABLE, FILLING }

    /** hoursToFull is null unless FILLING; gbPerDay is the fitted growth rate (negative = shrinking). */
    public record Forecast(Status status, Double hoursToFull, double gbPerDay) {}

    static final long MIN_SPAN_MS = 30 * 60_000L;
    static final int MIN_POINTS = 10;
    static final double STABLE_BYTES_PER_DAY = 50e6;
    private static final double MS_PER_DAY = 86_400_000.0;

    private DiskForecast() {}

    /** @param samples {timeMillis, usedBytes}, oldest first */
    public static Forecast forecast(List<long[]> samples, long freeBytes) {
        if (samples.size() < MIN_POINTS
                || samples.get(samples.size() - 1)[0] - samples.get(0)[0] < MIN_SPAN_MS) {
            return new Forecast(Status.COLLECTING, null, 0);
        }
        // fit used = a + slope * t, with t relative to the first sample to keep the numbers small
        long t0 = samples.get(0)[0];
        double n = samples.size(), sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (long[] s : samples) {
            double x = s[0] - t0;
            double y = s[1];
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        double denom = n * sxx - sx * sx;
        double slope = denom == 0 ? 0 : (n * sxy - sx * sy) / denom; // bytes per ms
        double perDay = slope * MS_PER_DAY;
        double gbPerDay = Math.round(perDay / 1e9 * 100) / 100.0;
        if (perDay < STABLE_BYTES_PER_DAY) {
            return new Forecast(Status.STABLE, null, gbPerDay);
        }
        double hours = Math.max(0, freeBytes) / slope / 3_600_000.0;
        return new Forecast(Status.FILLING, Math.round(hours * 10) / 10.0, gbPerDay);
    }
}
