package ru.itmo.advisor.domain.model;

import java.time.Instant;
import java.util.List;

/** История числового элемента за окно разбора, по возрастанию времени. */
public record MetricSeries(MonitoredItem item, List<Point> points) {

    public MetricSeries {
        points = List.copyOf(points);
    }

    public record Point(Instant time, double value) {
    }
}
