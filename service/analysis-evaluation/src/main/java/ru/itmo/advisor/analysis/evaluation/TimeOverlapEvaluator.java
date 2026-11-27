package ru.itmo.advisor.analysis.evaluation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.EvaluationReport;
import ru.itmo.advisor.domain.model.EvaluationReport.Candidate;
import ru.itmo.advisor.domain.model.EvaluationReport.MatchStatus;
import ru.itmo.advisor.domain.model.FaultLabel;
import ru.itmo.advisor.domain.model.HostRef;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.Problem;
import ru.itmo.advisor.domain.model.Suspect;
import ru.itmo.advisor.domain.model.TimeWindow;
import ru.itmo.advisor.domain.port.QualityEvaluator;

/** Временное совпадение начала инцидента с полуинтервалом [start, end + 2 минуты). */
public class TimeOverlapEvaluator implements QualityEvaluator {

    private static final Duration GRACE = Duration.ofMinutes(2);
    private static final Comparator<Incident> INCIDENT_ORDER = Comparator.comparing(Incident::startedAt)
            .thenComparing(Incident::id);
    private static final Comparator<FaultLabel> LABEL_ORDER = Comparator.comparing(FaultLabel::start)
            .thenComparing(FaultLabel::end)
            .thenComparing(FaultLabel::scenarioId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(FaultLabel::faultId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(FaultLabel::type, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(FaultLabel::source, Comparator.nullsFirst(Comparator.naturalOrder()));

    @Override
    public EvaluationReport evaluate(List<FaultLabel> labels, List<Incident> incidents) {
        return evaluateInternal(labels, incidents, null);
    }

    @Override
    public EvaluationReport evaluate(List<FaultLabel> labels, List<Incident> incidents, TimeWindow observationWindow) {
        Objects.requireNonNull(observationWindow, "observationWindow");
        if (observationWindow.from() == null || observationWindow.to() == null
                || !observationWindow.from().isBefore(observationWindow.to())) {
            throw new IllegalArgumentException("Окно наблюдения должно быть непустым полуинтервалом");
        }
        return evaluateInternal(labels, incidents, observationWindow);
    }

    private EvaluationReport evaluateInternal(List<FaultLabel> labels, List<Incident> incidents,
                                               TimeWindow observationWindow) {
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(incidents, "incidents");
        for (FaultLabel label : labels) {
            Objects.requireNonNull(label, "label");
            if (label.start() == null || label.end() == null || label.end().isBefore(label.start())) {
                throw new IllegalArgumentException("Некорректное окно сбоя");
            }
        }
        Set<UUID> ids = new HashSet<>();
        for (Incident incident : incidents) {
            Objects.requireNonNull(incident, "incident");
            if (incident.id() == null || incident.startedAt() == null || !ids.add(incident.id())) {
                throw new IllegalArgumentException("Инциденты должны иметь уникальные id и время начала");
            }
        }
        List<FaultLabel> orderedLabels = labels.stream().sorted(LABEL_ORDER).toList();
        List<Incident> orderedIncidents = incidents.stream()
                .filter(i -> observationWindow == null || observationWindow.contains(i.startedAt()))
                .sorted(INCIDENT_ORDER).toList();
        Map<UUID, Integer> incidence = new HashMap<>();
        List<List<Incident>> matches = new ArrayList<>();
        for (FaultLabel label : orderedLabels) {
            List<Incident> candidates = orderedIncidents.stream().filter(i -> overlaps(label, i)).toList();
            matches.add(candidates);
            for (Incident candidate : candidates) {
                incidence.merge(candidate.id(), 1, Integer::sum);
            }
        }

        List<EvaluationReport.Row> rows = new ArrayList<>();
        for (int index = 0; index < orderedLabels.size(); index++) {
            FaultLabel label = orderedLabels.get(index);
            List<Incident> incidentsForLabel = matches.get(index);
            List<Candidate> candidates = incidentsForLabel.stream()
                    .map(i -> candidate(label, i, incidence.get(i.id()) > 1)).toList();
            boolean ambiguous = candidates.stream().anyMatch(Candidate::ambiguous);
            Candidate firstAnalyzed = candidates.stream()
                    .filter(c -> c.analyzed() && !c.ambiguous()).findFirst().orElse(null);
            MatchStatus status = firstAnalyzed != null ? MatchStatus.MATCHED
                    : ambiguous ? MatchStatus.AMBIGUOUS
                    : candidates.isEmpty() ? MatchStatus.NO_INCIDENT : MatchStatus.NO_ANALYSIS;
            Duration timeToAnswer = null;
            String baselineSource = null;
            Boolean baselineCorrect = null;
            if (status == MatchStatus.MATCHED) {
                Incident selected = incidentsForLabel.stream()
                        .filter(i -> i.id().toString().equals(firstAnalyzed.incidentId())).findFirst().orElseThrow();
                baselineSource = baselineSource(selected);
                if (baselineSource != null && label.source() != null && !label.source().isBlank()) {
                    baselineCorrect = baselineSource.equals(label.source());
                }
                var completedAt = selected.analysis().completedAt();
                if (completedAt != null && !completedAt.isBefore(selected.startedAt())) {
                    timeToAnswer = Duration.between(label.start(), completedAt);
                }
            }
            rows.add(new EvaluationReport.Row(label.faultId(), label.type(), label.source(),
                    status == MatchStatus.MATCHED ? firstAnalyzed.predictedSource() : null,
                    status == MatchStatus.MATCHED ? firstAnalyzed.rankOfExpected() : null,
                    status == MatchStatus.MATCHED ? firstAnalyzed.incidentId() : null, status, candidates,
                    label.scenarioId(), timeToAnswer, baselineSource, baselineCorrect));
        }
        List<String> outside = observationWindow == null ? null : orderedIncidents.stream()
                .filter(i -> observationWindow.contains(i.startedAt()))
                .filter(i -> !incidence.containsKey(i.id()))
                .map(i -> i.id().toString()).toList();
        return EvaluationReport.summarize(rows, outside);
    }

    private static String baselineSource(Incident incident) {
        if (incident.problems().isEmpty() || incident.problems().stream().anyMatch(p -> p.startedAt() == null)) {
            return null;
        }
        var firstTime = incident.problems().stream().map(Problem::startedAt).min(Comparator.naturalOrder()).orElseThrow();
        Set<String> hosts = new HashSet<>();
        for (Problem problem : incident.problems()) {
            if (!firstTime.equals(problem.startedAt())) {
                continue;
            }
            if (problem.hosts().isEmpty()) {
                return null;
            }
            for (HostRef host : problem.hosts()) {
                if (host.host() == null || host.host().isBlank()) {
                    return null;
                }
                hosts.add(host.host());
            }
        }
        return hosts.size() == 1 ? hosts.iterator().next() : null;
    }

    private static boolean overlaps(FaultLabel label, Incident incident) {
        return !incident.startedAt().isBefore(label.start())
                && incident.startedAt().isBefore(label.end().plus(GRACE));
    }

    private static Candidate candidate(FaultLabel label, Incident incident, boolean ambiguous) {
        if (incident.analysisStatus() != AnalysisStatus.DONE || incident.analysis() == null) {
            return new Candidate(incident.id().toString(), ambiguous, false, null, null);
        }
        List<String> ranked = incident.analysis().suspects().stream().map(Suspect::host).toList();
        int rank = label.source() == null || label.source().isBlank() ? -1 : ranked.indexOf(label.source());
        return new Candidate(incident.id().toString(), ambiguous, true, incident.analysis().topSuspect(),
                rank < 0 ? null : rank + 1);
    }
}
