package ru.itmo.advisor.domain.model;

import java.time.Instant;
import java.util.List;

/** Итог разбора инцидента. Сохраняется целиком и отдаётся интерфейсу. */
public record AnalysisResult(
        Instant analyzedAt,
        List<String> scope,
        List<Suspect> suspects,
        List<MetricDeviation> deviations,
        List<LogEvidence> logEvidence,
        Explanation explanation) {

    public AnalysisResult {
        scope = scope == null ? List.of() : List.copyOf(scope);
        suspects = suspects == null ? List.of() : List.copyOf(suspects);
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
        logEvidence = logEvidence == null ? List.of() : List.copyOf(logEvidence);
    }

    /** Самый вероятный первоисточник или {@code null}. */
    public String topSuspect() {
        return suspects.isEmpty() ? null : suspects.get(0).host();
    }
}
