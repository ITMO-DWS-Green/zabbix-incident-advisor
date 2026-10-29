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

/** Хранилище в памяти для тестов без БД. */
class InMemoryIncidentRepository implements IncidentRepository {

    final Map<UUID, Incident> byId = new LinkedHashMap<>();

    @Override
    public Optional<Incident> createIfAbsent(Incident i) {
        if (findByEventId(i.problemEventId()).isPresent()) {
            return Optional.empty();
        }
        byId.put(i.id(), i);
        return Optional.of(i);
    }

    @Override
    public Optional<Incident> find(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Incident> findByEventId(long e) {
        return byId.values().stream().filter(i -> i.problemEventId() == e).findFirst();
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
        findByEventId(e).ifPresent(i -> byId.put(i.id(), with(i, IncidentStatus.RESOLVED, at, i.analysisStatus(),
                i.analysis(), i.analysisError())));
    }

    @Override
    public void setAnalysisStatus(UUID id, AnalysisStatus s) {
        Incident i = byId.get(id);
        byId.put(id, with(i, i.status(), i.resolvedAt(), s, i.analysis(), i.analysisError()));
    }

    @Override
    public void saveAnalysis(UUID id, AnalysisResult r) {
        Incident i = byId.get(id);
        byId.put(id, with(i, i.status(), i.resolvedAt(), AnalysisStatus.DONE, r, null));
    }

    @Override
    public void saveFailure(UUID id, String error) {
        Incident i = byId.get(id);
        byId.put(id, with(i, i.status(), i.resolvedAt(), AnalysisStatus.FAILED, i.analysis(), error));
    }

    private static Incident with(Incident i, IncidentStatus st, Instant resolved, AnalysisStatus as,
                                 AnalysisResult a, String err) {
        return new Incident(i.id(), i.problemEventId(), i.triggerName(), i.severity(), i.hosts(), i.tags(),
                i.startedAt(), resolved, st, as, a, err);
    }
}
