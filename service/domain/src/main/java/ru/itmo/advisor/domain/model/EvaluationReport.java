package ru.itmo.advisor.domain.model;

import java.time.Duration;
import java.util.List;

/**
 * Точность разбора на размеченных сбоях.
 *
 * @param faults         сколько сбоев в разметке
 * @param matched        сколько из них совпали по времени хотя бы с одним инцидентом
 * @param sourceCorrect  у скольких первым подозреваемым назван настоящий источник
 * @param sourceInTop3   у скольких настоящий источник в первых трёх
 * @param meanTimeToAnswer среднее время от начала сбоя до готового разбора
 */
public record EvaluationReport(int faults, int matched, int sourceCorrect, int sourceInTop3,
                               Duration meanTimeToAnswer, List<Row> rows) {

    public EvaluationReport {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public record Row(String faultId, String type, String expectedSource, String predictedSource,
                      Integer rankOfExpected, String incidentId) {
    }
}
