package ru.itmo.advisor.domain.model;

import java.time.Instant;

/**
 * Метрика заметно отклонилась от обычного поведения.
 *
 * @param startedAt когда отклонение началось — главный признак для поиска первоисточника
 * @param score     сила отклонения; больше — сильнее
 */
public record MetricDeviation(String host, String itemId, String metric, String units,
                              double expected, double actual, Instant startedAt, double score) {
}
