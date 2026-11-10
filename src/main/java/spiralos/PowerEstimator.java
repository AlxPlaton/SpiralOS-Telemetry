package spiralos;

import java.util.List;

import spiralos.TelemetryHistory.Point;

/**
 * Turns CPU load into an electricity estimate for home labs, using the simple linear model
 * most power-planning guides use: watts = idle + (full load - idle) x CPU%. The user enters
 * their own idle and full-load watts (ideally read from a plug-in power meter), so the
 * estimate is only as good as those two numbers, and the UI says so.
 */
public final class PowerEstimator {

    /**
     * @param wattsNow      estimated draw right now
     * @param wattsAvg      average over the history window
     * @param minutes       how much history the average covers
     * @param kwhPerMonth   wattsAvg kept up for an average month (30.44 days)
     * @param costPerMonth  in the user's currency, at their price per kWh
     */
    public record Estimate(double wattsNow, double wattsAvg, double minutes, double kwhPerMonth,
            double costPerMonth, int idleWatts, int maxWatts, int centsPerKwh) {}

    static final double HOURS_PER_MONTH = 30.44 * 24;

    private PowerEstimator() {}

    static double watts(double cpuPercent, AppSettings s) {
        double load = Math.max(0, Math.min(100, cpuPercent)) / 100;
        return s.idleWatts() + (s.maxWatts() - s.idleWatts()) * load;
    }

    public static Estimate estimate(List<Point> history, double cpuNow, AppSettings s) {
        double now = watts(cpuNow, s);
        double avg = now;
        double minutes = 0;
        if (!history.isEmpty()) {
            double sum = 0;
            for (Point p : history) {
                sum += watts(p.cpu(), s);
            }
            avg = sum / history.size();
            minutes = (history.get(history.size() - 1).t() - history.get(0).t()) / 60_000.0;
        }
        double kwh = avg * HOURS_PER_MONTH / 1000;
        double cost = kwh * s.centsPerKwh() / 100;
        return new Estimate(round(now, 1), round(avg, 1), round(minutes, 1), round(kwh, 1), round(cost, 2),
                s.idleWatts(), s.maxWatts(), s.centsPerKwh());
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
