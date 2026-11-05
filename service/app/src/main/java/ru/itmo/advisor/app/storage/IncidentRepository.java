package ru.itmo.advisor.app.storage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ru.itmo.advisor.domain.model.AnalysisResult;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.Problem;

public interface IncidentRepository {

    /** Создать вместе с его проблемами, если ни одной из них ещё нет ни в каком инциденте. */
    Optional<Incident> createIfAbsent(Incident incident);

    /**
     * Добавить проблему в инцидент и пересчитать его шапку.
     *
     * @return инцидент после добавления; пусто, если такая проблема уже есть или инцидента нет
     */
    Optional<Incident> addProblem(UUID incidentId, Problem problem);

    Optional<Incident> find(UUID id);

    /** Инцидент, в который входит проблема с таким eventId. */
    Optional<Incident> findByEventId(long problemEventId);

    /** Новые первыми. */
    List<Incident> findRecent(int limit);

    List<Incident> findOpen();

    List<Incident> findByAnalysisStatus(AnalysisStatus status);

    List<Incident> findAll();

    /** Закрыть проблему; инцидент закрывается, когда закрыты все его проблемы. */
    void resolve(long problemEventId, Instant resolvedAt);

    void setAnalysisStatus(UUID id, AnalysisStatus status);

    void saveAnalysis(UUID id, AnalysisResult result);

    void saveFailure(UUID id, String error);
}
