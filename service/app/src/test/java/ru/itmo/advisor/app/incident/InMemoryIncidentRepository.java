package ru.itmo.advisor.app.incident;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ru.itmo.advisor.app.storage.IncidentRepository;
import ru.itmo.advisor.domain.model.AnalysisResult;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.IncidentStatus;
import ru.itmo.advisor.domain.model.Problem;

/** Хранилище в памяти для тестов без БД. */
class InMemoryIncidentRepository implements IncidentRepository {

    final Map<UUID, Incident> byId = new LinkedHashMap<>();

    @Override
    public Optional<Incident> createIfAbsent(Incident i) {
        if (i.problems().stream().anyMatch(p -> findByEventId(p.eventId()).isPresent())) {
            return Optional.empty();
        }
        byId.put(i.id(), i);
        return Optional.of(i);
    }

    @Override
    public Optional<Incident> addProblem(UUID incidentId, Problem problem) {
        Incident i = byId.get(incidentId);
        if (i == null || findByEventId(problem.eventId()).isPresent()) {
            return Optional.empty();
        }
        Incident updated = i.withProblem(problem);
        byId.put(incidentId, updated);
        return Optional.of(updated);
    }

    @Override
    public Optional<Incident> find(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Incident> findByEventId(long e) {
        return byId.values().stream().filter(i -> i.problemEventIds().contains(e)).findFirst();
    }

    @Override
    public List<Incident> findRecent(int limit) {
        return byId.values().stream().sorted(Comparator.comparing(Incident::startedAt).reversed()).limit(limit).toList();
    }

    @Override
    public List<Incident> findOpen() {
        return byId.values().stream().filter(i -> i.status() == IncidentStatus.OPEN).toList();
    }

    @Override
    public List<Incident> findByAnalysisStatus(AnalysisStatus s) {
        return byId.values().stream().filter(i -> i.analysisStatus() == s).toList();
    }

    @Override
    public List<Incident> findAll() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public void resolve(long e, Instant at) {
        findByEventId(e).ifPresent(i -> byId.put(i.id(), i.withProblems(i.problems().stream()
                .map(p -> p.eventId() == e && p.isOpen()
                        ? new Problem(p.eventId(), p.triggerName(), p.severity(), p.hosts(), p.tags(), p.startedAt(), at)
                        : p)
                .toList())));
    }

    @Override
    public void setAnalysisStatus(UUID id, AnalysisStatus s) {
        Incident i = byId.get(id);
        byId.put(id, with(i, s, i.analysis(), i.analysisError()));
    }

    @Override
    public void saveAnalysis(UUID id, AnalysisResult r) {
        Incident i = byId.get(id);
        byId.put(id, with(i, AnalysisStatus.DONE, r, null));
    }

    @Override
    public void saveFailure(UUID id, String error) {
        Incident i = byId.get(id);
        byId.put(id, with(i, AnalysisStatus.FAILED, i.analysis(), error));
    }

    private static Incident with(Incident i, AnalysisStatus as, AnalysisResult a, String err) {
        return new Incident(i.id(), i.problemEventId(), i.triggerName(), i.severity(), i.hosts(), i.tags(),
                i.startedAt(), i.resolvedAt(), i.status(), as, a, err, i.problems());
    }
}
