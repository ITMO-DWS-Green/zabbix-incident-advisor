package ru.itmo.advisor.app.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.advisor.domain.model.AnalysisResult;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.HostRef;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.IncidentStatus;
import ru.itmo.advisor.domain.model.Problem;
import ru.itmo.advisor.domain.model.Tag;

/** Инцидент — строка {@code incident} (шапка и разбор) и его проблемы в {@code incident_problem}. */
@Repository
public class JdbcIncidentRepository implements IncidentRepository {

    private static final String COLUMNS = "id, problem_event_id, trigger_name, severity, hosts, tags, started_at, "
            + "resolved_at, status, analysis_status, analysis, analysis_error";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcIncidentRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public Optional<Incident> createIfAbsent(Incident i) {
        for (Problem p : i.problems()) {
            if (problemExists(p.eventId())) {
                return Optional.empty();
            }
        }
        int inserted = jdbc.sql("""
                        INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, tags, started_at,
                                              resolved_at, status, analysis_status)
                        VALUES (:id, :eventId, :name, :severity, CAST(:hosts AS jsonb), CAST(:tags AS jsonb),
                                :startedAt, :resolvedAt, :status, :analysisStatus)
                        ON CONFLICT (problem_event_id) DO NOTHING""")
                .param("id", i.id())
                .param("eventId", i.problemEventId())
                .param("name", i.triggerName())
                .param("severity", i.severity())
                .param("hosts", write(i.hosts()))
                .param("tags", write(i.tags()))
                .param("startedAt", Timestamp.from(i.startedAt()))
                .param("resolvedAt", timestamp(i.resolvedAt()))
                .param("status", i.status().name())
                .param("analysisStatus", i.analysisStatus().name())
                .update();
        if (inserted != 1) {
            return Optional.empty();
        }
        i.problems().forEach(p -> insertProblem(i.id(), p));
        return Optional.of(i);
    }

    @Override
    @Transactional
    public Optional<Incident> addProblem(UUID incidentId, Problem problem) {
        Optional<Incident> incident = find(incidentId);
        if (incident.isEmpty() || insertProblem(incidentId, problem) != 1) {
            return Optional.empty();
        }
        Incident updated = incident.get().withProblem(problem);
        updateHeader(updated);
        return Optional.of(updated);
    }

    @Override
    public Optional<Incident> find(UUID id) {
        return withProblems(jdbc.sql("SELECT " + COLUMNS + " FROM incident WHERE id = :id").param("id", id)
                .query(this::map).list()).stream().findFirst();
    }

    @Override
    public Optional<Incident> findByEventId(long eventId) {
        return withProblems(jdbc.sql("SELECT " + COLUMNS + " FROM incident WHERE id = "
                        + "(SELECT incident_id FROM incident_problem WHERE event_id = :e)")
                .param("e", eventId).query(this::map).list()).stream().findFirst();
    }

    @Override
    public List<Incident> findRecent(int limit) {
        return withProblems(jdbc.sql("SELECT " + COLUMNS + " FROM incident ORDER BY started_at DESC LIMIT :limit")
                .param("limit", limit).query(this::map).list());
    }

    @Override
    public List<Incident> findOpen() {
        return withProblems(jdbc.sql("SELECT " + COLUMNS + " FROM incident WHERE status = 'OPEN' ORDER BY started_at")
                .query(this::map).list());
    }

    @Override
    public List<Incident> findByAnalysisStatus(AnalysisStatus status) {
        return withProblems(jdbc.sql("SELECT " + COLUMNS + " FROM incident WHERE analysis_status = :s "
                        + "ORDER BY started_at")
                .param("s", status.name()).query(this::map).list());
    }

    @Override
    public List<Incident> findAll() {
        return withProblems(jdbc.sql("SELECT " + COLUMNS + " FROM incident ORDER BY started_at")
                .query(this::map).list());
    }

    @Override
    @Transactional
    public void resolve(long eventId, Instant resolvedAt) {
        int updated = jdbc.sql("UPDATE incident_problem SET resolved_at = :at WHERE event_id = :e "
                        + "AND resolved_at IS NULL")
                .param("at", Timestamp.from(resolvedAt)).param("e", eventId).update();
        if (updated == 1) {
            findByEventId(eventId).ifPresent(i -> updateHeader(i.withProblems(i.problems())));
        }
    }

    @Override
    public void setAnalysisStatus(UUID id, AnalysisStatus status) {
        jdbc.sql("UPDATE incident SET analysis_status = :s, updated_at = now() WHERE id = :id")
                .param("s", status.name()).param("id", id).update();
    }

    @Override
    public void saveAnalysis(UUID id, AnalysisResult result) {
        jdbc.sql("UPDATE incident SET analysis = CAST(:a AS jsonb), analysis_status = 'DONE', analysis_error = NULL, "
                        + "updated_at = now() WHERE id = :id")
                .param("a", write(result)).param("id", id).update();
    }

    @Override
    public void saveFailure(UUID id, String error) {
        jdbc.sql("UPDATE incident SET analysis_status = 'FAILED', analysis_error = :e, updated_at = now() "
                        + "WHERE id = :id")
                .param("e", error).param("id", id).update();
    }

    private boolean problemExists(long eventId) {
        return jdbc.sql("SELECT count(*) FROM incident_problem WHERE event_id = :e").param("e", eventId)
                .query(Long.class).single() > 0;
    }

    private int insertProblem(UUID incidentId, Problem p) {
        return jdbc.sql("""
                        INSERT INTO incident_problem (event_id, incident_id, trigger_name, severity, hosts, tags,
                                                      started_at, resolved_at)
                        VALUES (:eventId, :incidentId, :name, :severity, CAST(:hosts AS jsonb), CAST(:tags AS jsonb),
                                :startedAt, :resolvedAt)
                        ON CONFLICT (event_id) DO NOTHING""")
                .param("eventId", p.eventId())
                .param("incidentId", incidentId)
                .param("name", p.triggerName())
                .param("severity", p.severity())
                .param("hosts", write(p.hosts()))
                .param("tags", write(p.tags()))
                .param("startedAt", Timestamp.from(p.startedAt()))
                .param("resolvedAt", timestamp(p.resolvedAt()))
                .update();
    }

    /** Шапка инцидента — сводка по его проблемам (см. {@link Incident#withProblems}). */
    private void updateHeader(Incident i) {
        jdbc.sql("""
                        UPDATE incident SET problem_event_id = :eventId, trigger_name = :name, severity = :severity,
                               hosts = CAST(:hosts AS jsonb), tags = CAST(:tags AS jsonb), started_at = :startedAt,
                               resolved_at = :resolvedAt, status = :status, updated_at = now()
                        WHERE id = :id""")
                .param("id", i.id())
                .param("eventId", i.problemEventId())
                .param("name", i.triggerName())
                .param("severity", i.severity())
                .param("hosts", write(i.hosts()))
                .param("tags", write(i.tags()))
                .param("startedAt", Timestamp.from(i.startedAt()))
                .param("resolvedAt", timestamp(i.resolvedAt()))
                .param("status", i.status().name())
                .update();
    }

    /** Подгрузить проблемы одним запросом на весь список. */
    private List<Incident> withProblems(List<Incident> incidents) {
        if (incidents.isEmpty()) {
            return incidents;
        }
        Map<UUID, List<Problem>> byIncident = jdbc.sql("SELECT * FROM incident_problem WHERE incident_id IN (:ids)")
                .param("ids", incidents.stream().map(Incident::id).toList())
                .query((rs, n) -> Map.entry(rs.getObject("incident_id", UUID.class), mapProblem(rs)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return incidents.stream().map(i -> new Incident(i.id(), i.problemEventId(), i.triggerName(), i.severity(),
                i.hosts(), i.tags(), i.startedAt(), i.resolvedAt(), i.status(), i.analysisStatus(), i.analysis(),
                i.analysisError(), byIncident.get(i.id()))).toList();
    }

    private Problem mapProblem(ResultSet rs) throws SQLException {
        Timestamp resolved = rs.getTimestamp("resolved_at");
        return new Problem(
                rs.getLong("event_id"),
                rs.getString("trigger_name"),
                rs.getInt("severity"),
                read(rs.getString("hosts"), new TypeReference<List<HostRef>>() { }),
                read(rs.getString("tags"), new TypeReference<List<Tag>>() { }),
                rs.getTimestamp("started_at").toInstant(),
                resolved == null ? null : resolved.toInstant());
    }

    private Incident map(ResultSet rs, int row) throws SQLException {
        Timestamp resolved = rs.getTimestamp("resolved_at");
        String analysis = rs.getString("analysis");
        return new Incident(
                rs.getObject("id", UUID.class),
                rs.getLong("problem_event_id"),
                rs.getString("trigger_name"),
                rs.getInt("severity"),
                read(rs.getString("hosts"), new TypeReference<List<HostRef>>() { }),
                read(rs.getString("tags"), new TypeReference<List<Tag>>() { }),
                rs.getTimestamp("started_at").toInstant(),
                resolved == null ? null : resolved.toInstant(),
                IncidentStatus.valueOf(rs.getString("status")),
                AnalysisStatus.valueOf(rs.getString("analysis_status")),
                analysis == null ? null : read(analysis, new TypeReference<AnalysisResult>() { }),
                rs.getString("analysis_error"));
    }

    private static Timestamp timestamp(Instant t) {
        return t == null ? null : Timestamp.from(t);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
