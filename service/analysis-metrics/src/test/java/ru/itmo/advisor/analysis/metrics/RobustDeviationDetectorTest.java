package ru.itmo.advisor.analysis.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.domain.model.*;

class RobustDeviationDetectorTest {
    private static final Instant START = Instant.parse("2026-11-20T12:00:00Z");
    private static final Instant SPLIT = START.minusSeconds(300);
    private final RobustDeviationDetector detector = new RobustDeviationDetector();

    @Test
    void detectsFirstPointOfSustainedRunDespiteBaselineOutlier() {
        var points = baseline(100);
        points.set(2, new MetricSeries.Point(points.get(2).time(), 1_000_000));
        add(points, SPLIT, 1000, 100, 200, 200, 200);
        var deviations = detect(points);
        assertThat(deviations).hasSize(1);
        assertThat(deviations.getFirst().startedAt()).isEqualTo(SPLIT.plusSeconds(20));
        assertThat(deviations.getFirst().expected()).isEqualTo(100);
    }

    @Test
    void noisyNormalAndIsolatedSpikesDoNotTrigger() {
        var points = new ArrayList<MetricSeries.Point>();
        for (int i = 0; i < 30; i++) points.add(new MetricSeries.Point(SPLIT.minusSeconds(300 - i * 10), 100 + 20 * Math.sin(i)));
        add(points, SPLIT, 115, 90, 1000, 105, 88, 500, 100, 90, 110);
        assertThat(detect(points)).isEmpty();
    }

    @Test
    void directionChangeAndGapsBreakPersistence() {
        var points = baseline(100);
        add(points, SPLIT, 200, 200, 0, 0);
        add(points, SPLIT.plusSeconds(200), 0, 100, 200, 200);
        assertThat(detect(points)).isEmpty();
    }

    @Test
    void detectsDecreaseAndZeroBaselineWithoutInfiniteScores() {
        var down = baseline(100);
        add(down, SPLIT, 0, 0, 0);
        assertThat(detect(down).getFirst().actual()).isZero();
        var zero = baseline(0);
        add(zero, SPLIT, 1, 1, 1);
        assertThat(detect(zero).getFirst().score()).isFinite().isPositive();
    }

    @Test
    void scoreIsComparableAcrossUnits() {
        var a = baseline(100);
        add(a, SPLIT, 200, 200, 200);
        var b = a.stream().map(p -> new MetricSeries.Point(p.time(), p.value() / 1000)).toList();
        assertThat(detect(a).getFirst().score()).isCloseTo(detect(b).getFirst().score(), within(1e-9));
    }

    @Test
    void invalidValuesResetRunAndInsufficientHistoryProducesNoGuess() {
        var points = baseline(100);
        add(points, SPLIT, 200, Double.NaN, 200, Double.POSITIVE_INFINITY, 200, 200);
        assertThat(detect(points)).isEmpty();
        var shortHistory = new ArrayList<>(baseline(100).subList(0, 4));
        add(shortHistory, SPLIT, 200, 200, 200);
        assertThat(detect(shortHistory)).isEmpty();
    }

    @Test
    void sortsPointsAndDoesNotCountDuplicateTimestamps() {
        var points = baseline(100);
        for (int i = 0; i < 3; i++) points.add(new MetricSeries.Point(SPLIT, 200));
        Collections.reverse(points);
        assertThat(detect(points)).isEmpty();
        add(points, SPLIT.plusSeconds(10), 200, 200);
        assertThat(detect(points).getFirst().startedAt()).isEqualTo(SPLIT);
    }

    @Test
    void ignoresValuesOutsideAnalysisWindow() {
        var points = baseline(100);
        add(points, START.plusSeconds(120), 200, 200, 200);
        assertThat(detect(points)).isEmpty();
    }

    @Test
    void dailySeasonalitySuppressesExpectedNoonRiseButFindsFault() {
        var points = baseline(100);
        add(points, START, 200, 200, 200);
        assertThat(detector.detect(context(points, trends(false).subList(0, 20)))).isEmpty();
        add(points, START.plusSeconds(30), 400, 400, 400);
        var d = detector.detect(context(points, trends(false).subList(0, 20))).getFirst();
        assertThat(d.startedAt()).isEqualTo(START.plusSeconds(30));
        assertThat(d.expected()).isEqualTo(200);
    }

    @Test
    void weeklySlotWinsOverDailyAverage() {
        var points = baseline(100);
        add(points, START, 500, 500, 500);
        assertThat(detector.detect(context(points, trends(true)))).isEmpty();
    }

    @Test
    void overlappingFutureAndInvalidTrendsCannotTeachAwayAFault() {
        var points = baseline(100);
        add(points, START, 200, 200, 200);
        var trends = new ArrayList<MetricSeries.HourlyTrend>();
        for (int i = 0; i < 10; i++) {
            trends.add(new MetricSeries.HourlyTrend(START.plus(Duration.ofDays(i)), 100, 200, 200, 200));
            trends.add(new MetricSeries.HourlyTrend(START.minus(Duration.ofDays(i + 1)), 0, 200, 200, 200));
        }
        trends.add(new MetricSeries.HourlyTrend(START.minusSeconds(3600), 100, 100, 200, 200));
        assertThat(detector.detect(context(points, trends))).hasSize(1);
    }

    @Test
    void sparseTrendsFallBackToRawHistory() {
        var points = baseline(100);
        add(points, START, 200, 200, 200);
        assertThat(detector.detect(context(points, trends(false).subList(0, 2)))).hasSize(1);
    }

    private List<MetricDeviation> detect(List<MetricSeries.Point> points) {
        return detector.detect(context(points, List.of()));
    }

    private static List<MetricSeries.Point> baseline(double value) {
        List<MetricSeries.Point> result = new ArrayList<>();
        for (int i = 0; i < 12; i++) result.add(new MetricSeries.Point(SPLIT.minusSeconds(120 - i * 10), value));
        return result;
    }

    private static void add(List<MetricSeries.Point> points, Instant first, double... values) {
        for (int i = 0; i < values.length; i++) points.add(new MetricSeries.Point(first.plusSeconds(i * 10), values[i]));
    }

    private static List<MetricSeries.HourlyTrend> trends(boolean weekly) {
        List<MetricSeries.HourlyTrend> result = new ArrayList<>();
        for (int day = 1; day <= 27; day++) {
            Instant noon = START.minus(Duration.ofDays(day));
            double value = weekly && day % 7 == 0 ? 500 : 200;
            result.add(new MetricSeries.HourlyTrend(noon.minusSeconds(3600), 100, 100, 100, 100));
            result.add(new MetricSeries.HourlyTrend(noon, 100, value, value, value));
        }
        return result;
    }

    private static IncidentContext context(List<MetricSeries.Point> points, List<MetricSeries.HourlyTrend> trends) {
        Incident incident = new Incident(UUID.randomUUID(), 1, "test", 3, List.of(new HostRef("database", "database")),
                List.of(), START, null, IncidentStatus.OPEN, AnalysisStatus.PENDING, null, null);
        var item = new MonitoredItem("1", "database", "latency", "latency", 0, "ms", List.of());
        return new IncidentContext(incident, new TimeWindow(START.minusSeconds(900), START.plusSeconds(120)),
                ServiceGraph.empty(), List.of("database"), List.of(new MetricSeries(item, points, trends)), List.of());
    }
}
