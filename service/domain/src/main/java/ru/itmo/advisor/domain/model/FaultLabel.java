package ru.itmo.advisor.domain.model;

import java.time.Instant;

/**
 * Разметка сбоя от test-server — эталон для оценки точности.
 *
 * @param source сервис, в котором сбой начался
 */
public record FaultLabel(String scenarioId, String faultId, String type, String source, Instant start, Instant end) {
}
