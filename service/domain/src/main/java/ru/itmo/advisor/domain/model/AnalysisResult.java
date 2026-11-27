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
        Explanation explanation,
        Instant completedAt) {

    public AnalysisResult {
        scope = scope == null ? List.of() : List.copyOf(scope);
        suspects = suspects == null ? List.of() : List.copyOf(suspects);
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
        logEvidence = logEvidence == null ? List.of() : List.copyOf(logEvidence);
    }

    /** Старые результаты не содержат достоверного времени завершения. */
    public AnalysisResult(Instant analyzedAt, List<String> scope, List<Suspect> suspects,
                          List<MetricDeviation> deviations, List<LogEvidence> logEvidence, Explanation explanation) {
        this(analyzedAt, scope, suspects, deviations, logEvidence, explanation, null);
    }

    /** Самый вероятный первоисточник или {@code null}. */
    public String topSuspect() {
        return suspects.isEmpty() ? null : suspects.get(0).host();
    }
}
