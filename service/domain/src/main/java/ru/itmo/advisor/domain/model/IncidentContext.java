package ru.itmo.advisor.domain.model;

import java.util.List;

/**
 * Всё, что известно к моменту разбора. Собирает инфраструктура (ContextLoader в app),
 * бизнес-логика только читает.
 *
 * @param window  окно разбора: от «начала инцидента минус lookback» до момента разбора
 * @param scope   сервисы в разборе: хосты проблемы и их соседи по графу
 */
public record IncidentContext(
        Incident incident,
        TimeWindow window,
        ServiceGraph graph,
        List<String> scope,
        List<MetricSeries> metrics,
        List<LogSeries> logs) {

    public IncidentContext {
        scope = List.copyOf(scope);
        metrics = List.copyOf(metrics);
        logs = List.copyOf(logs);
    }

    public List<MetricSeries> metricsOf(String host) {
        return metrics.stream().filter(m -> m.item().host().equals(host)).toList();
    }

    public List<LogSeries> logsOf(String host) {
        return logs.stream().filter(l -> l.item().host().equals(host)).toList();
    }
}
