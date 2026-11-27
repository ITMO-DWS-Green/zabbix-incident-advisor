package ru.itmo.advisor.app.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.advisor.domain.model.AnalysisResult;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.FaultLabel;
import ru.itmo.advisor.domain.model.HostRef;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.IncidentStatus;
import ru.itmo.advisor.domain.model.Problem;
import ru.itmo.advisor.domain.model.Suspect;
import ru.itmo.advisor.domain.model.TimeWindow;
import ru.itmo.advisor.domain.port.QualityEvaluator;

@Service
public class EvaluationRunService {
    public static final String SCOPE = "window_approximation";
    public static final String METHODOLOGY = "time-overlap-v1";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_LABELS = 1000;
    public static final int MAX_INCIDENTS = 1000;
    public static final int MAX_PAIRS = 100_000;
    public static final int MAX_PROBLEMS_PER_INCIDENT = 100;
    public static final int MAX_HOSTS_PER_PROBLEM = 100;
    public static final int MAX_SUSPECTS_PER_INCIDENT = 100;
    public static final int MAX_PROBLEMS = 10_000;
    public static final int MAX_HOSTS = 100_000;
    public static final int MAX_SUSPECTS = 10_000;
    /** UTF-8 bytes, not Java characters; limits apply to all snapshot technical strings. */
    public static final int MAX_STRING_BYTES = 1024;
    public static final int MAX_INPUT_JSON_BYTES = 4 * 1024 * 1024;
    public static final int MAX_SNAPSHOT_JSON_BYTES = 16 * 1024 * 1024;
    public static final Instant MIN_DATE = Instant.EPOCH;
    public static final Instant MAX_DATE = Instant.parse("2100-01-01T00:00:00Z");

    public static class Validation extends RuntimeException {
        public Validation(String message) {
            super(message);
        }
    }

    private final EvaluationRunRepository repository;
    private final QualityEvaluator evaluator;
    private final ObjectMapper json;

    public EvaluationRunService(EvaluationRunRepository repository, QualityEvaluator evaluator, ObjectMapper json) {
        this.repository = repository;
        this.evaluator = evaluator;
        this.json = json;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public EvaluationRun create(Instant from, Instant to) {
        validate(from, to);
        List<FaultLabel> labels = repository.labels(from, to);
        if (labels.size() > MAX_LABELS) {
            throw new Validation("Слишком много меток для окна: максимум " + MAX_LABELS);
        }
        int incidentCount = repository.incidentCount(from, to);
        if (incidentCount > MAX_INCIDENTS) {
            throw new Validation("Слишком много инцидентов для окна: максимум " + MAX_INCIDENTS);
        }
        if ((long) labels.size() * incidentCount > MAX_PAIRS) {
            throw new Validation("Слишком много пар метка/инцидент: максимум " + MAX_PAIRS);
        }
        repository.validateSourceBudget(from, to);
        repository.validateSourceJson(from, to);
        var incidentRows = repository.incidents(from, to);
        if (incidentRows.size() != incidentCount) {
            throw new IllegalStateException("Incident count changed within evaluation snapshot");
        }
        Map<UUID, List<EvaluationRun.ProblemInput>> problems = new HashMap<>();
        for (var row : repository.problems(from, to)) {
            problems.computeIfAbsent(row.incidentId(), ignored -> new ArrayList<>())
                    .add(new EvaluationRun.ProblemInput(row.startedAt(), strings(row.hostsJson())));
        }
        List<EvaluationRun.IncidentInput> inputs = incidentRows.stream()
                .map(row -> new EvaluationRun.IncidentInput(row.id(), row.startedAt(), row.analysisStatus(),
                        row.analysisPresent(), completedAt(row.completedAt()),
                        strings(row.rankedSourcesJson()), problems.getOrDefault(row.id(), List.of())))
                .toList();
        List<Incident> incidents = inputs.stream().map(EvaluationRunService::toIncident).toList();
        // Work on copies: a mutable evaluator implementation must not alter the captured inputs.
        var report = evaluator.evaluate(new ArrayList<>(labels), new ArrayList<>(incidents), new TimeWindow(from, to));
        EvaluationRun run = new EvaluationRun(UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MICROS),
                from, to, SCHEMA_VERSION, METHODOLOGY, SCOPE, labels, inputs, report);
        repository.insert(run);
        return run;
    }

    public Optional<EvaluationRun> find(UUID id) {
        return repository.find(id);
    }

    public List<EvaluationRun.Summary> list(int limit, int offset) {
        return repository.list(limit, offset);
    }

    private static void validate(Instant from, Instant to) {
        // PostgreSQL timestamps have microsecond precision. Reject values that would change on persistence.
        if (from == null || to == null || !from.isBefore(to)
                || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0
                || from.getNano() % 1000 != 0 || to.getNano() % 1000 != 0
                || from.isBefore(MIN_DATE) || to.isAfter(MAX_DATE)) {
            throw new Validation("from и to: UTC timestamps с точностью до микросекунды, "
                    + "from < to, окно не более 31 дня, диапазон 1970-01-01–2100-01-01 включительно");
        }
    }

    private Instant completedAt(String value) {
        try {
            if (value == null) {
                return null;
            }
            var node = json.readTree(value);
            // Integers may represent either epoch seconds or milliseconds; their unit is unknown.
            if (node.isIntegralNumber() || node.isNull()) {
                return null;
            }
            return json.treeToValue(node, Instant.class);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new Validation("Повреждённые данные: некорректный analysis.completedAt");
        }
    }

    private List<String> strings(String value) {
        try {
            // Null host names are meaningful to baseline and must not be discarded.
            return json.readValue(value, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException | RuntimeException e) {
            throw new Validation("Повреждённые данные: некорректная проекция входных данных");
        }
    }

    private static Incident toIncident(EvaluationRun.IncidentInput input) {
        List<Problem> problems = input.problems().stream().map(p -> new Problem(0, "", 0,
                p.hosts().stream().map(h -> new HostRef(h, null)).toList(), List.of(), p.startedAt(), null)).toList();
        AnalysisResult analysis = input.analysisPresent()
                ? new AnalysisResult(null, List.of(), input.rankedSources().stream()
                        .map(host -> new Suspect(host, 0, null, List.of())).toList(), List.of(), List.of(), null,
                        input.completedAt()) : null;
        return new Incident(input.id(), 0, "", 0, List.of(), List.of(), input.startedAt(), null,
                IncidentStatus.OPEN, AnalysisStatus.valueOf(input.analysisStatus()), analysis, null, problems);
    }
}
