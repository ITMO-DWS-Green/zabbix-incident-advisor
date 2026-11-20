package ru.itmo.advisor.analysis.metrics;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.MetricDeviation;
import ru.itmo.advisor.domain.model.MetricSeries;
import ru.itmo.advisor.domain.port.MetricDeviationDetector;

/** Median/MAD baseline; three consecutive, same-direction outliers confirm an onset. */
public final class RobustDeviationDetector implements MetricDeviationDetector {
    // Zabbix's trigger can lag behind the actual fault. Never train on this interval.
    public static final Duration LEAD = Duration.ofMinutes(5);
    private static final int MIN_BASELINE = 5;
    private static final int RUN = 3;
    private static final double THRESHOLD = 3.5;

    @Override
    public List<MetricDeviation> detect(IncidentContext context) {
        Instant split = context.incident().startedAt().minus(LEAD);
        List<MetricDeviation> result = new ArrayList<>();
        for (MetricSeries series : context.metrics()) {
            // Duplicate timestamps cannot confirm persistence. Sorting is defensive for custom sources.
            Map<Instant, MetricSeries.Point> unique = new TreeMap<>();
            series.points().stream().filter(p -> context.window().contains(p.time()))
                    .forEach(p -> unique.putIfAbsent(p.time(), p));
            List<MetricSeries.Point> baseline = unique.values().stream()
                    .filter(p -> p.time().isBefore(split) && Double.isFinite(p.value())).toList();
            if (baseline.size() < MIN_BASELINE) {
                continue;
            }
            Baseline raw = baseline(baseline);
            Seasonal seasonal = new Seasonal(series.trends(), split);
            List<MetricSeries.Point> residuals = baseline.stream().filter(p -> seasonal.at(p.time()) != null)
                    .map(p -> new MetricSeries.Point(p.time(), p.value() - seasonal.at(p.time()).center())).toList();
            Baseline residual = residuals.size() >= MIN_BASELINE ? baseline(residuals) : null;
            double maxGap = 3 * cadence(baseline);
            MetricSeries.Point first = null, previous = null;
            int count = 0, direction = 0;
            double firstExpected = 0, runStrength = 0;
            for (MetricSeries.Point point : unique.values()) {
                if (point.time().isBefore(split)) {
                    continue;
                }
                Stats slot = residual == null ? null : seasonal.at(point.time());
                double expected = slot == null ? raw.expected(point.time()) : slot.center() + residual.expected(point.time());
                double spread = slot == null ? raw.spread() : Math.max(residual.spread(), slot.spread());
                double scale = Math.max(spread, Math.max(Math.abs(expected) * 0.05, 1e-9));
                double z = (point.value() - expected) / scale;
                if (!Double.isFinite(z) || Math.abs(z) <= THRESHOLD) {
                    first = null;
                    previous = null;
                    count = 0;
                    continue;
                }
                int sign = z > 0 ? 1 : -1;
                if (first == null || sign != direction
                        || Duration.between(previous.time(), point.time()).toMillis() / 1000.0 > maxGap) {
                    first = point;
                    firstExpected = expected;
                    count = 0;
                    direction = sign;
                    runStrength = Double.POSITIVE_INFINITY;
                }
                previous = point;
                count++;
                runStrength = Math.min(runStrength, Math.abs(z));
                if (count == RUN) {
                    result.add(new MetricDeviation(series.item().host(), series.item().itemId(),
                            series.item().name(), series.item().units(), firstExpected, first.value(),
                            first.time(), runStrength));
                    break;
                }
            }
        }
        result.sort(Comparator.comparing(MetricDeviation::startedAt)
                .thenComparing(MetricDeviation::host).thenComparing(MetricDeviation::itemId));
        return result;
    }

    /** Bounded Theil–Sen slope: a few historical outliers do not turn a ramp into a fault. */
    private static Baseline baseline(List<MetricSeries.Point> points) {
        Instant anchor = points.get(points.size() / 2).time();
        List<MetricSeries.Point> sample = new ArrayList<>();
        int count = Math.min(32, points.size());
        for (int i = 0; i < count; i++) sample.add(points.get(i * (points.size() - 1) / (count - 1)));
        List<Double> slopes = new ArrayList<>();
        for (int i = 0; i < sample.size(); i++) {
            for (int j = i + 1; j < sample.size(); j++) {
                double seconds = seconds(sample.get(i).time(), sample.get(j).time());
                double slope = (sample.get(j).value() - sample.get(i).value()) / seconds;
                if (seconds > 0 && Double.isFinite(slope)) slopes.add(slope);
            }
        }
        double slope = slopes.isEmpty() ? 0 : median(slopes);
        List<Double> adjusted = points.stream().map(p -> p.value() - slope * seconds(anchor, p.time())).toList();
        if (adjusted.stream().anyMatch(v -> !Double.isFinite(v))) {
            return new Baseline(anchor, stats(points.stream().map(MetricSeries.Point::value).toList()), 0);
        }
        return new Baseline(anchor, stats(adjusted), slope);
    }

    private static double seconds(Instant from, Instant to) {
        return Duration.between(from, to).toNanos() / 1e9;
    }

    private record Baseline(Instant anchor, Stats residual, double slope) {
        double expected(Instant time) { return residual.center() + slope * seconds(anchor, time); }
        double spread() { return residual.spread(); }
    }

    private static double cadence(List<MetricSeries.Point> points) {
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            gaps.add(Duration.between(points.get(i - 1).time(), points.get(i).time()).toNanos() / 1e9);
        }
        return median(gaps);
    }

    private static Stats stats(List<Double> values) {
        double center = median(values);
        return new Stats(center, 1.4826 * median(values.stream().map(v -> Math.abs(v - center)).toList()));
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : sorted.get(n / 2 - 1) / 2 + sorted.get(n / 2) / 2;
    }

    private record Stats(double center, double spread) { }

    /** Weekly slot needs three weeks; daily slot needs seven days. All slots use UTC. */
    private static final class Seasonal {
        private final Map<Integer, Stats> weekly = new HashMap<>();
        private final Map<Integer, Stats> daily = new HashMap<>();

        Seasonal(List<MetricSeries.HourlyTrend> trends, Instant split) {
            Map<Instant, MetricSeries.HourlyTrend> unique = new TreeMap<>();
            trends.stream().filter(t -> t.samples() > 0 && Double.isFinite(t.min())
                            && Double.isFinite(t.avg()) && Double.isFinite(t.max())
                            && t.min() <= t.avg() && t.avg() <= t.max()
                            && !t.start().plusSeconds(3600).isAfter(split)
                            && !t.start().isBefore(split.minus(Duration.ofDays(28))))
                    .forEach(t -> unique.putIfAbsent(t.start(), t));
            Map<Integer, List<MetricSeries.HourlyTrend>> weeks = new HashMap<>(), days = new HashMap<>();
            unique.values().forEach(t -> {
                var hour = t.start().atZone(ZoneOffset.UTC);
                weeks.computeIfAbsent(hour.getDayOfWeek().getValue() * 24 + hour.getHour(), k -> new ArrayList<>()).add(t);
                days.computeIfAbsent(hour.getHour(), k -> new ArrayList<>()).add(t);
            });
            weeks.forEach((key, rows) -> { if (rows.size() >= 3) weekly.put(key, aggregate(rows)); });
            days.forEach((key, rows) -> { if (rows.size() >= 7) daily.put(key, aggregate(rows)); });
        }

        private static Stats aggregate(List<MetricSeries.HourlyTrend> rows) {
            Stats means = stats(rows.stream().map(MetricSeries.HourlyTrend::avg).toList());
            // Hourly averages hide within-hour variability: do not treat the mean as an exact raw value.
            double range = median(rows.stream().map(t -> Math.max(t.avg() - t.min(), t.max() - t.avg())).toList());
            return new Stats(means.center(), Math.max(means.spread(), range));
        }

        Stats at(Instant time) {
            var hour = time.atZone(ZoneOffset.UTC);
            return weekly.getOrDefault(hour.getDayOfWeek().getValue() * 24 + hour.getHour(), daily.get(hour.getHour()));
        }
    }
}
