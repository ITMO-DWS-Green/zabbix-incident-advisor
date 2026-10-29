package ru.itmo.advisor.app.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.itmo.advisor.domain.model.AnalysisResult;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.HostRef;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.IncidentStatus;
import ru.itmo.advisor.domain.model.Tag;

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
    public Optional<Incident> createIfAbsent(Incident i) {
        int inserted = jdbc.sql("""
                        INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, tags, started_at,
                                              status, analysis_status)
                        VALUES (:id, :eventId, :name, :severity, CAST(:hosts AS jsonb), CAST(:tags AS jsonb),
                                :startedAt, :status, :analysisStatus)
                        ON CONFLICT (problem_event_id) DO NOTHING""")
                .param("id", i.id())
                .param("eventId", i.problemEventId())
                .param("name", i.triggerName())
                .param("severity", i.severity())
                .param("hosts", write(i.hosts()))
                .param("tags", write(i.tags()))
                .param("startedAt", Timestamp.from(i.startedAt()))
                .param("status", i.status().name())
                .param("analysisStatus", i.analysisStatus().name())
                .update();
        return inserted == 1 ? Optional.of(i) : Optional.empty();
    }

    @Override
    public Optional<Incident> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM incident WHERE id = :id").param("id", id)
                .query(this::map).optional();
    }

    @Override
    public Optional<Incident> findByEventId(long eventId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM incident WHERE problem_event_id = :e").param("e", eventId)
                .query(this::map).optional();
    }

    @Override
    public List<Incident> findRecent(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM incident ORDER BY started_at DESC LIMIT :limit")
                .param("limit", limit).query(this::map).list();
    }

    @Override
    public List<Incident> findOpen() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM incident WHERE status = 'OPEN' ORDER BY started_at")
                .query(this::map).list();
    }

    @Override
    public List<Incident> findByAnalysisStatus(AnalysisStatus status) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM incident WHERE analysis_status = :s ORDER BY started_at")
                .param("s", status.name()).query(this::map).list();
    }

    @Override
    public List<Incident> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM incident ORDER BY started_at").query(this::map).list();
    }

    @Override
    public void resolve(long eventId, Instant resolvedAt) {
        jdbc.sql("UPDATE incident SET status = 'RESOLVED', resolved_at = :at, updated_at = now() "
                        + "WHERE problem_event_id = :e")
                .param("at", Timestamp.from(resolvedAt)).param("e", eventId).update();
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
