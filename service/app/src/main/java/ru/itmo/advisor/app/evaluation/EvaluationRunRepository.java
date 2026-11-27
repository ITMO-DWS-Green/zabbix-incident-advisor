package ru.itmo.advisor.app.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.itmo.advisor.domain.model.AnalysisResult;
import ru.itmo.advisor.domain.model.FaultLabel;
import ru.itmo.advisor.domain.model.HostRef;
import ru.itmo.advisor.domain.model.Tag;

@Repository
public class EvaluationRunRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ObjectMapper sourceJson;
    private static final TypeReference<AnalysisResult> ANALYSIS_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<HostRef>> HOSTS_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<Tag>> TAGS_TYPE = new TypeReference<>() { };

    public EvaluationRunRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.sourceJson = json;
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
    }

    /** Include every scenario whose matching interval intersects the observation window. */
    public List<FaultLabel> labels(Instant from, Instant to) {
        return jdbc.sql("""
                WITH selected AS MATERIALIZED (
                    SELECT scenario_id, fault_id, type, source, started_at, ended_at
                    FROM fault_label
                    WHERE started_at < :to AND ended_at + interval '2 minutes' > :from
                    ORDER BY started_at, ended_at, scenario_id, fault_id LIMIT :limit
                ), checked AS (
                    SELECT *, greatest(octet_length(scenario_id), octet_length(fault_id),
                                       octet_length(type), octet_length(source)) AS max_bytes FROM selected
                )
                SELECT CASE WHEN max_bytes <= :max_string THEN scenario_id END AS scenario_id,
                       CASE WHEN max_bytes <= :max_string THEN fault_id END AS fault_id,
                       CASE WHEN max_bytes <= :max_string THEN type END AS type,
                       CASE WHEN max_bytes <= :max_string THEN source END AS source,
                       max_bytes, started_at, ended_at
                FROM checked ORDER BY checked.started_at, checked.ended_at, checked.scenario_id, checked.fault_id
                """).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("limit", EvaluationRunService.MAX_LABELS + 1)
                .param("max_string", EvaluationRunService.MAX_STRING_BYTES)
                .query((rs, n) -> {
                    if (rs.getInt("max_bytes") > EvaluationRunService.MAX_STRING_BYTES) {
                        throw new EvaluationRunService.Validation("Слишком длинная техническая строка метки: максимум "
                                + EvaluationRunService.MAX_STRING_BYTES + " байт UTF-8");
                    }
                    return new FaultLabel(rs.getString("scenario_id"), rs.getString("fault_id"),
                            rs.getString("type"), rs.getString("source"), rs.getTimestamp("started_at").toInstant(),
                            rs.getTimestamp("ended_at").toInstant());
                }).list();
    }

    /** Count no more than the sentinel; REPEATABLE READ keeps the later materialization consistent. */
    public int incidentCount(Instant from, Instant to) {
        return jdbc.sql("""
                SELECT count(*) FROM (
                    SELECT 1 FROM incident WHERE started_at >= :from AND started_at < :to LIMIT :limit
                ) selected
                """).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("limit", EvaluationRunService.MAX_INCIDENTS + 1).query(Integer.class).single();
    }

    /** Check the complete source budget before any JSON crosses the JDBC boundary. */
    public void validateSourceBudget(Instant from, Instant to) {
        jdbc.sql("""
                WITH selected AS MATERIALIZED (
                    SELECT id, hosts, tags, analysis FROM incident
                    WHERE started_at >= :from AND started_at < :to LIMIT :incident_limit
                ), selected_problems AS MATERIALIZED (
                    SELECT p.incident_id, p.hosts, p.tags FROM incident_problem p
                    JOIN selected i ON i.id = p.incident_id LIMIT :problem_limit
                ), per_incident AS (
                    SELECT incident_id, count(*) AS n FROM selected_problems GROUP BY incident_id
                )
                SELECT (SELECT count(*) FROM selected) AS incidents,
                       (SELECT count(*) FROM selected_problems) AS problems,
                       COALESCE((SELECT max(n) FROM per_incident), 0) AS max_problems,
                       COALESCE((SELECT sum(octet_length(hosts::text)::bigint
                           + octet_length(tags::text)::bigint + COALESCE(octet_length(analysis::text), 0))
                           FROM selected), 0)
                       + COALESCE((SELECT sum(octet_length(hosts::text)::bigint + octet_length(tags::text)::bigint)
                           FROM selected_problems), 0) AS json_bytes
                """).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("incident_limit", EvaluationRunService.MAX_INCIDENTS + 1)
                .param("problem_limit", EvaluationRunService.MAX_PROBLEMS + 1)
                .query((rs, n) -> {
                    checkSourceBudget(rs.getLong("incidents"), rs.getLong("problems"),
                            rs.getLong("max_problems"), rs.getLong("json_bytes"));
                    return Boolean.TRUE;
                }).single();
    }

    static void checkSourceBudget(long incidents, long problems, long perIncident, long bytes) {
        if (incidents > EvaluationRunService.MAX_INCIDENTS) {
            throw new EvaluationRunService.Validation("Слишком много инцидентов для окна: максимум "
                    + EvaluationRunService.MAX_INCIDENTS);
        }
        if (problems > EvaluationRunService.MAX_PROBLEMS) {
            throw new EvaluationRunService.Validation("Слишком много problems для окна: максимум "
                    + EvaluationRunService.MAX_PROBLEMS);
        }
        if (perIncident > EvaluationRunService.MAX_PROBLEMS_PER_INCIDENT) {
            throw new EvaluationRunService.Validation("Слишком много problems в инциденте: максимум "
                    + EvaluationRunService.MAX_PROBLEMS_PER_INCIDENT);
        }
        if (bytes > EvaluationRunService.MAX_INPUT_JSON_BYTES) {
            throw new EvaluationRunService.Validation("Слишком большой JSON входных данных: максимум "
                    + EvaluationRunService.MAX_INPUT_JSON_BYTES + " байт");
        }
    }

    /** Discard validated objects immediately; raw source JSON never enters the snapshot. */
    public void validateSourceJson(Instant from, Instant to) {
        jdbc.sql("""
                SELECT hosts::text AS hosts, tags::text AS tags, analysis::text AS analysis
                FROM incident WHERE started_at >= :from AND started_at < :to
                """).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rs, n) -> {
                    validateSourceRow(rs.getString("hosts"), rs.getString("tags"), rs.getString("analysis"), true);
                    return Boolean.TRUE;
                }).list();
        jdbc.sql("""
                SELECT p.hosts::text AS hosts, p.tags::text AS tags
                FROM incident_problem p JOIN incident i ON i.id = p.incident_id
                WHERE i.started_at >= :from AND i.started_at < :to
                """).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rs, n) -> {
                    validateSourceRow(rs.getString("hosts"), rs.getString("tags"), null, false);
                    return Boolean.TRUE;
                }).list();
    }

    void validateSourceRow(String hosts, String tags, String analysis, boolean incident) {
        if (incident && analysis != null) {
            try {
                sourceJson.readValue(analysis, ANALYSIS_TYPE);
            } catch (JsonProcessingException | RuntimeException e) {
                throw new EvaluationRunService.Validation("Повреждённые данные: некорректный analysis или analysis.suspects");
            }
        }
        validateList(hosts, HOSTS_TYPE, "hosts");
        validateList(tags, TAGS_TYPE, "tags");
    }

    private <T> void validateList(String value, TypeReference<List<T>> type, String field) {
        try {
            List<T> parsed = sourceJson.readValue(value, type);
            if (parsed != null) {
                List.copyOf(parsed); // Incident and Problem constructors reject null elements.
            }
        } catch (JsonProcessingException | RuntimeException e) {
            throw new EvaluationRunService.Validation("Повреждённые данные: некорректный " + field);
        }
    }

    /** Fetch only fields needed by the evaluator; never fetch raw evidence or explanations. */
    public List<IncidentRow> incidents(Instant from, Instant to) {
        int[] suspects = {0};
        List<IncidentRow> rows = jdbc.sql("""
                WITH selected AS MATERIALIZED (
                    SELECT id, started_at, analysis_status,
                           (analysis IS NOT NULL AND analysis <> 'null'::jsonb) AS analysis_present,
                           (analysis IS NOT NULL AND jsonb_typeof(analysis) NOT IN ('object', 'null')) AS invalid_analysis,
                           octet_length(analysis_status) AS status_bytes,
                           octet_length((analysis -> 'completedAt')::text) AS completed_bytes,
                           CASE WHEN analysis -> 'suspects' IS NULL OR analysis -> 'suspects' = 'null'::jsonb THEN 0
                                WHEN jsonb_typeof(analysis -> 'suspects') = 'array' THEN jsonb_array_length(analysis -> 'suspects')
                                ELSE -1 END AS suspect_count
                    FROM incident WHERE started_at >= :from AND started_at < :to
                    ORDER BY started_at, id LIMIT :limit
                ), counted AS (
                    SELECT *, row_number() OVER (ORDER BY started_at, id) AS incident_number,
                           sum(greatest(suspect_count, 0)) OVER (ORDER BY started_at, id) AS running_suspects FROM selected
                )
                , inspected AS MATERIALIZED (
                    SELECT c.*, x.max_host_bytes, x.json_bytes, x.invalid_suspect
                    FROM counted c JOIN incident i ON i.id = c.id
                    LEFT JOIN LATERAL (
                        -- Missing/null and scalar hosts are accepted by Jackson; null elements are not.
                        SELECT COALESCE(bool_or(jsonb_typeof(s.value) <> 'object'
                            OR COALESCE(jsonb_typeof(s.value -> 'host') IN ('object', 'array'), false)), false) AS invalid_suspect,
                               COALESCE(max(CASE WHEN jsonb_typeof(s.value -> 'host') IN ('object', 'array')
                            THEN :max_string + 1 ELSE octet_length(s.value ->> 'host') END), 0) AS max_host_bytes,
                               2 + COALESCE(sum(CASE WHEN jsonb_typeof(s.value -> 'host') IN ('object', 'array')
                                   OR octet_length(s.value ->> 'host') > :max_string
                                   THEN :max_json + 1 ELSE octet_length(COALESCE(s.value -> 'host', 'null'::jsonb)::text) + 2 END), 0) AS json_bytes
                        FROM jsonb_array_elements(CASE WHEN c.incident_number <= :max_incidents
                            AND c.suspect_count >= 0 AND c.suspect_count <= :max_suspects AND c.running_suspects <= :total_suspects
                            THEN COALESCE(NULLIF(i.analysis -> 'suspects', 'null'::jsonb), '[]'::jsonb)
                            ELSE '[]'::jsonb END) s(value)
                    ) x ON true
                ), budgeted AS (
                    SELECT *, sum(COALESCE(json_bytes, 0)) OVER (ORDER BY started_at, id) AS total_json_bytes
                    FROM inspected
                )
                SELECT c.id, c.started_at, c.analysis_present, c.invalid_analysis, c.invalid_suspect,
                       c.suspect_count, c.status_bytes, c.completed_bytes, c.max_host_bytes, c.total_json_bytes,
                       CASE WHEN c.status_bytes <= :max_string THEN c.analysis_status END AS analysis_status,
                       CASE WHEN c.completed_bytes <= :max_string THEN NULLIF((i.analysis -> 'completedAt')::text, 'null') END AS completed_at,
                       CASE WHEN c.incident_number <= :max_incidents AND c.suspect_count >= 0 AND c.suspect_count <= :max_suspects
                                 AND c.running_suspects <= :total_suspects AND NOT c.invalid_analysis
                                 AND NOT c.invalid_suspect AND c.max_host_bytes <= :max_string
                                 AND c.total_json_bytes <= :max_json
                           THEN COALESCE((SELECT jsonb_agg(s.value -> 'host' ORDER BY s.ordinality)
                               FROM jsonb_array_elements(COALESCE(NULLIF(i.analysis -> 'suspects', 'null'::jsonb), '[]'::jsonb))
                                    WITH ORDINALITY AS s(value, ordinality)), '[]'::jsonb)::text END AS ranked_sources
                FROM budgeted c JOIN incident i ON i.id = c.id ORDER BY c.started_at, c.id
                """).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("limit", EvaluationRunService.MAX_INCIDENTS + 1)
                .param("max_incidents", EvaluationRunService.MAX_INCIDENTS)
                .param("max_suspects", EvaluationRunService.MAX_SUSPECTS_PER_INCIDENT)
                .param("total_suspects", EvaluationRunService.MAX_SUSPECTS)
                .param("max_string", EvaluationRunService.MAX_STRING_BYTES)
                .param("max_json", EvaluationRunService.MAX_INPUT_JSON_BYTES)
                .query((rs, n) -> {
                    if (n >= EvaluationRunService.MAX_INCIDENTS) {
                        return new IncidentRow(rs.getObject("id", UUID.class),
                                rs.getTimestamp("started_at").toInstant(), rs.getString("analysis_status"),
                                rs.getBoolean("analysis_present"), rs.getString("completed_at"), null);
                    }
                    if (rs.getBoolean("invalid_analysis")) {
                        throw new EvaluationRunService.Validation("Повреждённые данные: analysis должен быть объектом или null");
                    }
                    int count = rs.getInt("suspect_count");
                    if (count < 0) {
                        throw new EvaluationRunService.Validation("Повреждённые данные: analysis.suspects должен быть массивом");
                    }
                    if (count > EvaluationRunService.MAX_SUSPECTS_PER_INCIDENT) {
                        throw new EvaluationRunService.Validation("Слишком много suspects в инциденте: максимум "
                                + EvaluationRunService.MAX_SUSPECTS_PER_INCIDENT);
                    }
                    suspects[0] += count;
                    if (suspects[0] > EvaluationRunService.MAX_SUSPECTS) {
                        throw new EvaluationRunService.Validation("Слишком много suspects для окна: максимум "
                                + EvaluationRunService.MAX_SUSPECTS);
                    }
                    if (rs.getBoolean("invalid_suspect")) {
                        throw new EvaluationRunService.Validation("Повреждённые данные: некорректный элемент analysis.suspects");
                    }
                    checkStringAndJsonLimits(rs.getInt("status_bytes"), rs.getInt("completed_bytes"),
                            rs.getInt("max_host_bytes"), rs.getLong("total_json_bytes"));
                    return new IncidentRow(rs.getObject("id", UUID.class),
                            rs.getTimestamp("started_at").toInstant(), rs.getString("analysis_status"),
                            rs.getBoolean("analysis_present"), rs.getString("completed_at"), rs.getString("ranked_sources"));
                }).list();
        return rows;
    }

    public List<ProblemRow> problems(Instant from, Instant to) {
        int[] totals = {0, 0};
        UUID[] previous = {null};
        int[] perIncident = {0};
        return jdbc.sql("""
                WITH selected AS MATERIALIZED (
                    SELECT i.id, i.started_at FROM incident i
                    WHERE i.started_at >= :from AND i.started_at < :to
                    ORDER BY i.started_at, i.id LIMIT :incident_limit
                ), first_problems AS MATERIALIZED (
                    SELECT i.id AS incident_id, COALESCE(p.started_at, i.started_at) AS started_at, p.event_id
                    FROM selected i
                    LEFT JOIN LATERAL (
                        SELECT p.event_id, p.started_at FROM incident_problem p
                        WHERE p.incident_id = i.id
                          AND p.started_at = (SELECT min(p2.started_at) FROM incident_problem p2 WHERE p2.incident_id = i.id)
                        LIMIT :problem_limit
                    ) p ON true
                    LIMIT :total_limit
                ), sizes AS MATERIALIZED (
                    SELECT f.incident_id, f.started_at, f.event_id,
                           CASE WHEN (CASE WHEN f.event_id IS NULL THEN i.hosts ELSE p.hosts END) = 'null'::jsonb THEN 0
                                WHEN jsonb_typeof(CASE WHEN f.event_id IS NULL THEN i.hosts ELSE p.hosts END) = 'array'
                                THEN jsonb_array_length(CASE WHEN f.event_id IS NULL THEN i.hosts ELSE p.hosts END)
                                ELSE -1 END AS host_count
                    FROM first_problems f JOIN incident i ON i.id = f.incident_id
                    LEFT JOIN incident_problem p ON p.event_id = f.event_id
                ), counted AS (
                    SELECT *, row_number() OVER (PARTITION BY incident_id ORDER BY started_at, event_id) AS incident_problems,
                           row_number() OVER (ORDER BY incident_id, started_at, event_id) AS total_problems,
                           sum(greatest(host_count, 0)) OVER (ORDER BY incident_id, started_at, event_id ROWS UNBOUNDED PRECEDING) AS total_hosts
                    FROM sizes
                )
                , inspected AS MATERIALIZED (
                    SELECT c.*, x.invalid_host_count, x.max_host_bytes, x.json_bytes
                    FROM counted c JOIN incident i ON i.id = c.incident_id
                    LEFT JOIN incident_problem p ON p.event_id = c.event_id
                    LEFT JOIN LATERAL (
                        SELECT count(*) FILTER (WHERE jsonb_typeof(h.value) <> 'object') AS invalid_host_count,
                               COALESCE(max(CASE WHEN jsonb_typeof(h.value -> 'host') IN ('object', 'array')
                            THEN :max_string + 1 ELSE octet_length(h.value ->> 'host') END), 0) AS max_host_bytes,
                               2 + COALESCE(sum(CASE WHEN jsonb_typeof(h.value -> 'host') IN ('object', 'array')
                                   OR octet_length(h.value ->> 'host') > :max_string
                                   THEN :max_json + 1 ELSE octet_length(COALESCE(h.value -> 'host', 'null'::jsonb)::text) + 2 END), 0) AS json_bytes
                        FROM jsonb_array_elements(CASE WHEN c.host_count >= 0 AND c.host_count <= :max_hosts AND c.total_hosts <= :total_hosts
                            AND c.incident_problems <= :max_problems AND c.total_problems <= :max_total_problems
                            THEN COALESCE(NULLIF(CASE WHEN c.event_id IS NULL THEN i.hosts ELSE p.hosts END, 'null'::jsonb), '[]'::jsonb)
                            ELSE '[]'::jsonb END) h(value)
                    ) x ON true
                ), budgeted AS (
                    SELECT *, sum(COALESCE(json_bytes, 0)) OVER (ORDER BY incident_id, started_at, event_id) AS total_json_bytes
                    FROM inspected
                )
                SELECT c.incident_id, c.started_at, c.host_count, c.invalid_host_count, c.max_host_bytes, c.total_json_bytes,
                       CASE WHEN c.host_count >= 0 AND c.host_count <= :max_hosts AND c.total_hosts <= :total_hosts
                                 AND c.incident_problems <= :max_problems AND c.total_problems <= :max_total_problems
                                 AND c.invalid_host_count = 0
                                 AND c.max_host_bytes <= :max_string AND c.total_json_bytes <= :max_json
                           THEN COALESCE((SELECT jsonb_agg(h.value -> 'host' ORDER BY h.ordinality)
                               FROM jsonb_array_elements(CASE
                                   WHEN jsonb_typeof(CASE WHEN c.event_id IS NULL THEN i.hosts ELSE p.hosts END) = 'array'
                                   THEN CASE WHEN c.event_id IS NULL THEN i.hosts ELSE p.hosts END
                                   ELSE '[]'::jsonb END)
                                    WITH ORDINALITY AS h(value, ordinality)), '[]'::jsonb)::text
                       END AS hosts
                FROM budgeted c JOIN incident i ON i.id = c.incident_id
                LEFT JOIN incident_problem p ON p.event_id = c.event_id
                ORDER BY c.incident_id, c.started_at, c.event_id
                """).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("incident_limit", EvaluationRunService.MAX_INCIDENTS + 1)
                .param("problem_limit", EvaluationRunService.MAX_PROBLEMS_PER_INCIDENT + 1)
                .param("total_limit", EvaluationRunService.MAX_PROBLEMS + 1)
                .param("max_hosts", EvaluationRunService.MAX_HOSTS_PER_PROBLEM)
                .param("total_hosts", EvaluationRunService.MAX_HOSTS)
                .param("max_problems", EvaluationRunService.MAX_PROBLEMS_PER_INCIDENT)
                .param("max_total_problems", EvaluationRunService.MAX_PROBLEMS)
                .param("max_string", EvaluationRunService.MAX_STRING_BYTES)
                .param("max_json", EvaluationRunService.MAX_INPUT_JSON_BYTES)
                .query((rs, n) -> {
                    UUID id = rs.getObject("incident_id", UUID.class);
                    if (!id.equals(previous[0])) {
                        previous[0] = id;
                        perIncident[0] = 0;
                    }
                    if (++perIncident[0] > EvaluationRunService.MAX_PROBLEMS_PER_INCIDENT) {
                        throw new EvaluationRunService.Validation("Слишком много первых problems в инциденте: максимум "
                                + EvaluationRunService.MAX_PROBLEMS_PER_INCIDENT);
                    }
                    if (++totals[0] > EvaluationRunService.MAX_PROBLEMS) {
                        throw new EvaluationRunService.Validation("Слишком много первых problems для окна: максимум "
                                + EvaluationRunService.MAX_PROBLEMS);
                    }
                    int hosts = rs.getInt("host_count");
                    if (hosts < 0) {
                        throw new EvaluationRunService.Validation("Повреждённые данные: hosts должен быть массивом");
                    }
                    if (hosts > EvaluationRunService.MAX_HOSTS_PER_PROBLEM) {
                        throw new EvaluationRunService.Validation("Слишком много hosts в problem: максимум "
                                + EvaluationRunService.MAX_HOSTS_PER_PROBLEM);
                    }
                    totals[1] += hosts;
                    if (totals[1] > EvaluationRunService.MAX_HOSTS) {
                        throw new EvaluationRunService.Validation("Слишком много hosts для окна: максимум "
                                + EvaluationRunService.MAX_HOSTS);
                    }
                    if (rs.getLong("invalid_host_count") != 0) {
                        throw new EvaluationRunService.Validation("Повреждённые данные: некорректный элемент hosts");
                    }
                    checkStringAndJsonLimits(rs.getInt("max_host_bytes"), 0, 0, rs.getLong("total_json_bytes"));
                    return new ProblemRow(id, rs.getTimestamp("started_at").toInstant(), rs.getString("hosts"));
                }).list();
    }

    private static void checkStringAndJsonLimits(int first, int second, int third, long jsonBytes) {
        if (Math.max(first, Math.max(second, third)) > EvaluationRunService.MAX_STRING_BYTES) {
            throw new EvaluationRunService.Validation("Слишком длинная техническая строка: максимум "
                    + EvaluationRunService.MAX_STRING_BYTES + " байт UTF-8");
        }
        if (jsonBytes > EvaluationRunService.MAX_INPUT_JSON_BYTES) {
            throw new EvaluationRunService.Validation("Слишком большой JSON входных данных: максимум "
                    + EvaluationRunService.MAX_INPUT_JSON_BYTES + " байт");
        }
    }

    public void insert(EvaluationRun run) {
        try {
            jdbc.sql("""
                    INSERT INTO evaluation_run (id, created_at, window_from, window_to, schema_version,
                                                methodology_version, payload)
                    VALUES (:id, :created, :from, :to, :schema, :methodology, CAST(:payload AS jsonb))
                    """).param("id", run.id()).param("created", Timestamp.from(run.createdAt()))
                    .param("from", Timestamp.from(run.from())).param("to", Timestamp.from(run.to()))
                    .param("schema", run.schemaVersion()).param("methodology", run.methodologyVersion())
                    .param("payload", serializeBounded(run)).update();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize evaluation snapshot", e);
        }
    }

    String serializeBounded(EvaluationRun run) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        OutputStream bounded = new OutputStream() {
            @Override public void write(int value) {
                if (buffer.size() >= EvaluationRunService.MAX_SNAPSHOT_JSON_BYTES) {
                    throw new EvaluationRunService.Validation("Слишком большой JSON снимка: максимум "
                            + EvaluationRunService.MAX_SNAPSHOT_JSON_BYTES + " байт");
                }
                buffer.write(value);
            }
            @Override public void write(byte[] bytes, int offset, int length) {
                if (length > EvaluationRunService.MAX_SNAPSHOT_JSON_BYTES - buffer.size()) {
                    throw new EvaluationRunService.Validation("Слишком большой JSON снимка: максимум "
                            + EvaluationRunService.MAX_SNAPSHOT_JSON_BYTES + " байт");
                }
                buffer.write(bytes, offset, length);
            }
        };
        try {
            json.writeValue(bounded, run);
        } catch (IOException e) {
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof EvaluationRunService.Validation validation) {
                    throw validation;
                }
            }
            throw e;
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    public Optional<EvaluationRun> find(UUID id) {
        return jdbc.sql("SELECT CAST(payload AS text) FROM evaluation_run WHERE id = :id").param("id", id)
                .query(String.class).optional().map(this::read);
    }

    public List<EvaluationRun.Summary> list(int limit, int offset) {
        return jdbc.sql("""
                SELECT id, created_at, window_from, window_to, schema_version, methodology_version, payload ->> 'scope' AS scope
                FROM evaluation_run ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset
                """).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new EvaluationRun.Summary(rs.getObject("id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("window_from").toInstant(),
                        rs.getTimestamp("window_to").toInstant(), rs.getInt("schema_version"),
                        rs.getString("methodology_version"), rs.getString("scope"))).list();
    }

    private EvaluationRun read(String value) {
        try {
            return json.readValue(value, EvaluationRun.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize evaluation snapshot", e);
        }
    }

    public record IncidentRow(UUID id, Instant startedAt, String analysisStatus, boolean analysisPresent,
                              String completedAt, String rankedSourcesJson) { }
    public record ProblemRow(UUID incidentId, Instant startedAt, String hostsJson) { }
}
