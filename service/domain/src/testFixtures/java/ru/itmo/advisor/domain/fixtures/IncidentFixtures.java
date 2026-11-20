package ru.itmo.advisor.domain.fixtures;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import ru.itmo.advisor.domain.model.FaultLabel;
import ru.itmo.advisor.domain.model.HostRef;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.LogSeries;
import ru.itmo.advisor.domain.model.MetricSeries;
import ru.itmo.advisor.domain.model.MonitoredItem;
import ru.itmo.advisor.domain.model.Problem;
import ru.itmo.advisor.domain.model.ServiceGraph;
import ru.itmo.advisor.domain.model.Tag;
import ru.itmo.advisor.domain.model.TimeWindow;
import ru.itmo.advisor.domain.port.ServiceGraphProvider.HostInfo;

public final class IncidentFixtures {

    public static final Instant START = Instant.parse("2026-10-29T12:00:00Z");
    public static final TimeWindow WINDOW = new TimeWindow(START.minusSeconds(900), START.plusSeconds(120));

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private IncidentFixtures() {
    }

    public static Incident incident(long eventId, String host, String trigger, Instant startedAt) {
        return Incident.open(new UUID(0, eventId), new Problem(eventId, trigger, 3,
                List.of(new HostRef(host, host)), List.of(), startedAt, null));
    }

    public static IncidentContext context(Incident incident, TimeWindow window, ServiceGraph graph,
                                          List<String> scope, List<MetricSeries> metrics, List<LogSeries> logs) {
        return new IncidentContext(incident, window, graph, scope, metrics, logs);
    }

    public static HostInfo host(String name, String... dependencies) {
        List<Tag> tags = dependencies.length == 0 ? List.of() : List.of(new Tag("depends_on", String.join(",", dependencies)));
        return new HostInfo(name, name, tags, List.of());
    }

    public static MetricSeries metricSeries(String host, String name, TimeWindow window, Duration step,
                                            ToDoubleFunction<Instant> valueAt) {
        if (step.isZero() || step.isNegative()) {
            throw new IllegalArgumentException("Шаг между точками должен быть положительным");
        }
        List<MetricSeries.Point> points = new ArrayList<>();
        for (Instant t = window.from(); t.isBefore(window.to()); t = t.plus(step)) {
            points.add(new MetricSeries.Point(t, valueAt.applyAsDouble(t)));
        }
        return new MetricSeries(new MonitoredItem(host + "-1", host, name, "k", 0, "", List.of()), points);
    }

    public static MetricSeries sustainedChange(String host, String name, TimeWindow window, Instant faultStart,
                                                double normal, double abnormal) {
        return metricSeries(host, name, window, Duration.ofSeconds(10),
                t -> (t.isBefore(faultStart) ? normal : abnormal) + Math.sin(t.getEpochSecond()) * normal * 0.02);
    }

    public static LogSeries.Line standLogLine(Instant time, String host, String level, String traceId, String logger,
                                             String message) {
        return new LogSeries.Line(time, "%s %-5s [%s] [%s] %s - %s".formatted(
                STAMP.format(time), level, host, traceId, logger, message), null, null);
    }

    public static LogSeries logSeries(String host, List<LogSeries.Line> lines) {
        return new LogSeries(new MonitoredItem(host + "-log", host, "Лог сервиса",
                "log[/var/log/stand/" + host + ".log,,,100]", 2, null, List.of()),
                lines.stream().sorted(Comparator.comparing(LogSeries.Line::time)).toList());
    }

    public static FaultLabel fault(String scenarioId, String faultId, String type, String source,
                                    Instant startedAt, Duration duration) {
        return new FaultLabel(scenarioId, faultId, type, source, startedAt, startedAt.plus(duration));
    }
}
