package ru.itmo.advisor.app.storage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ru.itmo.advisor.domain.model.AnalysisResult;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.Incident;

public interface IncidentRepository {

    /** Создать, если проблемы с таким eventId ещё нет. */
    Optional<Incident> createIfAbsent(Incident incident);

    Optional<Incident> find(UUID id);

    Optional<Incident> findByEventId(long problemEventId);

    /** Новые первыми. */
    List<Incident> findRecent(int limit);

    List<Incident> findOpen();

    List<Incident> findByAnalysisStatus(AnalysisStatus status);

    List<Incident> findAll();

    void resolve(long problemEventId, Instant resolvedAt);

    void setAnalysisStatus(UUID id, AnalysisStatus status);

    void saveAnalysis(UUID id, AnalysisResult result);

    void saveFailure(UUID id, String error);
}
