package ru.itmo.advisor.app.evaluation;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.advisor.app.storage.IncidentRepository;
import ru.itmo.advisor.app.storage.LabelRepository;
import ru.itmo.advisor.domain.model.EvaluationReport;
import ru.itmo.advisor.domain.model.EvaluationReport.Row;
import ru.itmo.advisor.domain.port.QualityEvaluator;

/**
 * Conservative per-request guard. It is not a database or process-wide resource guarantee:
 * concurrent requests and JSONB rendering inside PostgreSQL are not bounded.
 */
@Service
public class LiveEvaluationService {
    static final int MAX_LABELS = 1_000;
    static final int MAX_INCIDENTS = 1_000;
    static final int MAX_PROBLEMS = 10_000;
    static final long MAX_PAIRS = 100_000;
    static final long MAX_BYTES = 4L * 1024 * 1024;
    static final int MAX_SUSPECTS_PER_ANALYSIS = 10_000;
    static final long MAX_SUSPECT_WORK = 1_000_000;
    static final long MAX_RESPONSE_BYTES = 16L * 1024 * 1024;
    static final int MAX_SCENARIOS = 1_000;
    static final int MAX_SCENARIO_BYTES = 1_024;

    // Worst case JSON escaping uses six ASCII bytes per input UTF-8 byte. The fixed
    // allowances cover field names, UUID, booleans, numbers, duration and punctuation.
    static final long JSON_ESCAPE_FACTOR = 6;
    static final long CANDIDATE_OVERHEAD = 256;
    static final long ROW_OVERHEAD = 512;
    static final long REPORT_OVERHEAD = 1024;

    private final JdbcClient jdbc;
    private final LabelRepository labels;
    private final IncidentRepository incidents;
    private final QualityEvaluator evaluator;

    public LiveEvaluationService(JdbcClient jdbc, LabelRepository labels, IncidentRepository incidents,
                                 QualityEvaluator evaluator) {
        this.jdbc = jdbc;
        this.labels = labels;
        this.incidents = incidents;
        this.evaluator = evaluator;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public EvaluationReport evaluate(String scenarioId) {
        Selection selection = scenarioId == null ? Selection.all() : selectScenario(scenarioId);
        if (!selection.global && selection.labelIds.isEmpty()) {
            return EvaluationReport.summarize(List.of(), null);
        }
        long labelCount = selection.global ? count("fault_label", MAX_LABELS) : selection.labelIds.size();
        long incidentCount = selection.global ? count("incident", MAX_INCIDENTS) : selection.incidentIds.size();
        long problemCount = selection.global ? count("incident_problem", MAX_PROBLEMS)
                : selection.incidentIds.isEmpty() ? 0 : jdbc.sql("""
                    SELECT count(*) FROM (SELECT 1 FROM incident_problem WHERE incident_id IN (:ids)
                                          LIMIT :sentinel) bounded
                    """).param("ids", selection.incidentIds).param("sentinel", MAX_PROBLEMS + 1)
                    .query(Long.class).single();
        if (labelCount > MAX_LABELS || incidentCount > MAX_INCIDENTS || problemCount > MAX_PROBLEMS
                || exceedsProduct(labelCount, incidentCount, MAX_PAIRS)) {
            throw new LimitExceeded();
        }
        // SUM(bigint) is numeric in PostgreSQL; use BigInteger to avoid overflow before comparison.
        BigInteger bytes = size(selection, "fault_label", "fault_id", selection.labelIds, """
                octet_length(fault_id)::bigint + octet_length(scenario_id)::bigint
                + octet_length(type)::bigint + octet_length(source)::bigint
                """).add(size(selection, "incident", "id", selection.incidentIds, """
                octet_length(trigger_name)::bigint + octet_length(status)::bigint
                + octet_length(analysis_status)::bigint + COALESCE(octet_length(analysis_error), 0)::bigint
                + octet_length(hosts::text)::bigint + octet_length(tags::text)::bigint
                + COALESCE(octet_length(analysis::text), 0)::bigint
                """)).add(size(selection, "incident_problem", "incident_id", selection.incidentIds, """
                octet_length(trigger_name)::bigint + octet_length(hosts::text)::bigint
                + octet_length(tags::text)::bigint
                """));
        if (bytes.signum() < 0 || bytes.compareTo(BigInteger.valueOf(MAX_BYTES)) > 0) {
            throw new LimitExceeded();
        }
        // NULL/missing suspects mean an empty list. List.copyOf rejects null elements;
        // Jackson accepts missing/null and scalar hosts but not object/array hosts.
        long malformed = !selection.global && selection.incidentIds.isEmpty() ? 0 : incidentQuery(selection, """
                SELECT count(*) FROM incident
                WHERE (analysis IS NOT NULL AND jsonb_typeof(analysis) NOT IN ('object', 'null'))
                   OR jsonb_typeof(analysis -> 'suspects') NOT IN ('array', 'null')
                   OR EXISTS (
                       SELECT 1 FROM jsonb_array_elements(
                           CASE WHEN jsonb_typeof(analysis -> 'suspects') = 'array'
                                THEN analysis -> 'suspects' ELSE '[]'::jsonb END) AS s(value)
                       WHERE jsonb_typeof(s.value) <> 'object'
                          OR jsonb_typeof(s.value -> 'host') IN ('object', 'array')
                   )
                """, true).query(Long.class).single();
        if (malformed != 0) {
            throw new InvalidAnalysis();
        }
        BigInteger suspectCount = !selection.global && selection.incidentIds.isEmpty() ? BigInteger.ZERO : incidentQuery(selection, """
                SELECT COALESCE(SUM(CASE WHEN jsonb_typeof(analysis -> 'suspects') = 'array'
                    THEN jsonb_array_length(analysis -> 'suspects') ELSE 0 END), 0)
                FROM incident
                """, false).query(BigInteger.class).single();
        long oversizedAnalyses = !selection.global && selection.incidentIds.isEmpty() ? 0 : incidentQuery(selection, """
                SELECT count(*) FROM incident
                WHERE CASE WHEN jsonb_typeof(analysis -> 'suspects') = 'array'
                    THEN jsonb_array_length(analysis -> 'suspects') > :max ELSE false END
                """, true).param("max", MAX_SUSPECTS_PER_ANALYSIS).query(Long.class).single();
        if (oversizedAnalyses != 0 || suspectCount.signum() < 0
                || suspectCount.multiply(BigInteger.valueOf(labelCount))
                        .compareTo(BigInteger.valueOf(MAX_SUSPECT_WORK)) > 0) {
            throw new LimitExceeded();
        }
        // A candidate repeats the first suspect host for every label. JSONB text
        // bounds its decoded string even for Jackson's scalar-to-string coercions.
        // One input byte budget also covers all row-level labels, predictedSource
        // and baselineSource; multiply by labels for the latter two repetitions.
        BigInteger topSuspectBytes = !selection.global && selection.incidentIds.isEmpty() ? BigInteger.ZERO : incidentQuery(selection, """
                SELECT COALESCE(SUM(CASE WHEN jsonb_typeof(analysis -> 'suspects') = 'array'
                    THEN COALESCE(octet_length((analysis -> 'suspects' -> 0 -> 'host')::text), 0)
                    ELSE 0 END), 0) FROM incident
                """, false).query(BigInteger.class).single();
        if (responseBound(labelCount, incidentCount, bytes, topSuspectBytes)
                .compareTo(BigInteger.valueOf(MAX_RESPONSE_BYTES)) > 0) {
            throw new LimitExceeded();
        }
        EvaluationReport report = evaluator.evaluate(selection.global ? labels.find(null) : labels.findByIds(selection.labelIds),
                selection.global ? incidents.findAll() : incidents.findByIds(selection.incidentIds));
        if (scenarioId == null) {
            return report;
        }
        List<Row> rows = report.rows().stream().filter(row -> scenarioId.equals(row.scenarioId())).toList();
        return EvaluationReport.summarize(rows, null);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<String> scenarios() {
        long count = jdbc.sql("""
                SELECT count(*) FROM (SELECT DISTINCT scenario_id FROM fault_label LIMIT :sentinel) bounded
                """).param("sentinel", MAX_SCENARIOS + 1).query(Long.class).single();
        if (count > MAX_SCENARIOS) {
            throw new LimitExceeded();
        }
        long tooLong = jdbc.sql("""
                SELECT count(*) FROM fault_label WHERE octet_length(scenario_id) > :max
                """).param("max", MAX_SCENARIO_BYTES).query(Long.class).single();
        if (tooLong != 0) {
            throw new LimitExceeded();
        }
        return labels.scenarios();
    }

    private record Selection(boolean global, List<String> labelIds, List<UUID> incidentIds) {
        static Selection all() {
            return new Selection(true, List.of(), List.of());
        }
    }

    private Selection selectScenario(String scenarioId) {
        List<String> targets = jdbc.sql("SELECT fault_id FROM fault_label WHERE scenario_id = :scenario LIMIT :limit")
                .param("scenario", scenarioId).param("limit", MAX_LABELS + 1).query(String.class).list();
        if (targets.size() > MAX_LABELS) {
            throw new LimitExceeded();
        }
        if (targets.isEmpty()) {
            return new Selection(false, List.of(), List.of());
        }
        List<UUID> incidentIds = jdbc.sql("""
                SELECT i.id FROM incident i WHERE EXISTS (
                    SELECT 1 FROM fault_label l WHERE l.fault_id IN (:targets)
                      AND i.started_at >= l.started_at AND i.started_at < l.ended_at + interval '2 minutes'
                ) LIMIT :limit
                """).param("targets", targets).param("limit", MAX_INCIDENTS + 1).query(UUID.class).list();
        if (incidentIds.size() > MAX_INCIDENTS) {
            throw new LimitExceeded();
        }
        if (incidentIds.isEmpty()) {
            return new Selection(false, targets, incidentIds);
        }
        List<String> labelIds = jdbc.sql("""
                SELECT l.fault_id FROM fault_label l WHERE l.fault_id IN (:targets) OR EXISTS (
                    SELECT 1 FROM incident i WHERE i.id IN (:incidents)
                      AND i.started_at >= l.started_at AND i.started_at < l.ended_at + interval '2 minutes'
                ) LIMIT :limit
                """).param("targets", targets).param("incidents", incidentIds)
                .param("limit", MAX_LABELS + 1).query(String.class).list();
        if (labelIds.size() > MAX_LABELS) {
            throw new LimitExceeded();
        }
        return new Selection(false, labelIds, incidentIds);
    }

    private BigInteger size(Selection selection, String table, String column, List<?> ids, String expression) {
        if (!selection.global && ids.isEmpty()) {
            return BigInteger.ZERO;
        }
        var spec = jdbc.sql("SELECT COALESCE(SUM(" + expression + "), 0) FROM " + table
                + (selection.global ? "" : " WHERE " + column + " IN (:ids)"));
        if (!selection.global) {
            spec = spec.param("ids", ids);
        }
        return spec.query(BigInteger.class).single();
    }

    private JdbcClient.StatementSpec incidentQuery(Selection selection, String sql, boolean hasWhere) {
        if (selection.global) {
            return jdbc.sql(sql);
        }
        if (hasWhere) {
            int where = sql.indexOf("WHERE ");
            sql = sql.substring(0, where + 6) + "id IN (:ids) AND (" + sql.substring(where + 6) + ")";
        } else {
            sql += " WHERE id IN (:ids)";
        }
        return jdbc.sql(sql).param("ids", selection.incidentIds);
    }

    static BigInteger responseBound(long labels, long incidents, BigInteger inputBytes, BigInteger topBytes) {
        if (labels < 0 || incidents < 0 || inputBytes.signum() < 0 || topBytes.signum() < 0) {
            throw new LimitExceeded();
        }
        BigInteger l = BigInteger.valueOf(labels);
        return BigInteger.valueOf(REPORT_OVERHEAD)
                .add(l.multiply(BigInteger.valueOf(ROW_OVERHEAD)))
                .add(l.multiply(BigInteger.valueOf(incidents)).multiply(BigInteger.valueOf(CANDIDATE_OVERHEAD)))
                .add(inputBytes.multiply(l.add(BigInteger.ONE)).multiply(BigInteger.valueOf(JSON_ESCAPE_FACTOR)))
                .add(topBytes.multiply(l).multiply(BigInteger.valueOf(JSON_ESCAPE_FACTOR)));
    }

    private long count(String table, int limit) {
        return jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM " + table + " LIMIT :sentinel) bounded")
                .param("sentinel", limit + 1).query(Long.class).single();
    }

    static boolean exceedsProduct(long left, long right, long limit) {
        return left < 0 || right < 0 || limit < 0 || (right != 0 && left > limit / right);
    }

    public static final class InvalidAnalysis extends RuntimeException {
        public InvalidAnalysis() {
            super("Invalid analysis JSON: expected object and suspects array or null");
        }
    }

    public static final class LimitExceeded extends RuntimeException {
        public LimitExceeded() {
            super("Evaluation input exceeds emergency limits");
        }
    }
}
