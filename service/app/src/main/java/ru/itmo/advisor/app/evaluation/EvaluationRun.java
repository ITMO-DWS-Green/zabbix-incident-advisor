package ru.itmo.advisor.app.evaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import ru.itmo.advisor.domain.model.EvaluationReport;
import ru.itmo.advisor.domain.model.FaultLabel;

/** Only the evaluator's inputs, not the original incident or analysis payload. */
public record EvaluationRun(UUID id, Instant createdAt, Instant from, Instant to, int schemaVersion,
                            String methodologyVersion, String scope, List<FaultLabel> labels,
                            List<IncidentInput> incidents, EvaluationReport report) {
    public EvaluationRun {
        labels = List.copyOf(labels);
        incidents = List.copyOf(incidents);
    }

    public record IncidentInput(UUID id, Instant startedAt, String analysisStatus, boolean analysisPresent,
                                Instant completedAt, List<String> rankedSources, List<ProblemInput> problems) {
        public IncidentInput {
            rankedSources = Collections.unmodifiableList(new ArrayList<>(rankedSources));
            problems = List.copyOf(problems);
        }
    }

    public record ProblemInput(Instant startedAt, List<String> hosts) {
        public ProblemInput {
            hosts = Collections.unmodifiableList(new ArrayList<>(hosts));
        }
    }

    public record Summary(UUID id, Instant createdAt, Instant from, Instant to, int schemaVersion,
                          String methodologyVersion, String scope) { }
}
