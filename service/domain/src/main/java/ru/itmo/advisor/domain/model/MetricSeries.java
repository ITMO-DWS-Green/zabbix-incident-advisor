package ru.itmo.advisor.domain.model;

import java.time.Instant;
import java.util.List;

/** История за окно разбора и необязательная длинная история часовых агрегатов (UTC). */
public record MetricSeries(MonitoredItem item, List<Point> points, List<HourlyTrend> trends) {

    public MetricSeries {
        points = List.copyOf(points);
        trends = trends == null ? List.of() : List.copyOf(trends);
    }

    /** Совместимость с источниками, у которых есть только сырая история. */
    public MetricSeries(MonitoredItem item, List<Point> points) {
        this(item, points, List.of());
    }

    public record Point(Instant time, double value) {
    }

    /** Агрегат за [start, start + 1 час); samples — количество исходных значений. */
    public record HourlyTrend(Instant start, int samples, double min, double avg, double max) {
    }
}
