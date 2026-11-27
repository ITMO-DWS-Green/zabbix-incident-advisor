package ru.itmo.advisor.app.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.advisor.analysis.evaluation.TimeOverlapEvaluator;
import ru.itmo.advisor.domain.model.EvaluationReport.MatchStatus;
import ru.itmo.advisor.domain.model.FaultLabel;
import ru.itmo.advisor.domain.port.QualityEvaluator;

class EvaluationRunServiceTest {
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-02T00:00:00Z");
    private final EvaluationRunRepository repository = mock(EvaluationRunRepository.class);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final EvaluationRunService service = new EvaluationRunService(repository, new TimeOverlapEvaluator(), json);

    @org.junit.jupiter.api.BeforeEach
    void stubIncidentCount() {
        org.mockito.Mockito.lenient().when(repository.incidentCount(any(), any())).thenReturn(0);
    }

    @Test
    void capturesAllScenariosAmbiguityOutsideAndOnlyMinimalFields() throws Exception {
        UUID id = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        when(repository.incidentCount(FROM, TO)).thenReturn(2);
        when(repository.labels(FROM, TO)).thenReturn(List.of(
                new FaultLabel("scenario-a", "fault-a", "failure", "db", FROM, FROM.plusSeconds(30)),
                new FaultLabel("scenario-b", "fault-b", "failure", "db", FROM, FROM.plusSeconds(30))));
        when(repository.incidents(FROM, TO)).thenReturn(List.of(
                new EvaluationRunRepository.IncidentRow(id, FROM.plusSeconds(10), "DONE", true,
                        "\"2026-01-01T00:00:20Z\"", "[\"db\",\"api\"]"),
                new EvaluationRunRepository.IncidentRow(outside, FROM.plusSeconds(500), "PENDING", false,
                        null, "[]")));
        when(repository.problems(FROM, TO)).thenReturn(List.of(
                new EvaluationRunRepository.ProblemRow(id, FROM.plusSeconds(10), "[\"db\"]")));
        EvaluationRun run = service.create(FROM, TO);
        assertThat(run.id()).isNotNull();
        assertThat(run.scope()).isEqualTo("window_approximation");
        assertThat(run.labels()).hasSize(2);
        assertThat(run.report().rows()).allMatch(row -> row.matchStatus() == MatchStatus.AMBIGUOUS);
        assertThat(run.report().outsideWindowIncidentIds()).containsExactly(outside.toString());
        verify(repository).insert(run);
        String payload = json.writeValueAsString(run);
        assertThat(payload).doesNotContain("logEvidence", "explanation", "analysisError", "triggerName", "reasons");
        assertThat(json.readValue(payload, EvaluationRun.class)).isEqualTo(run);
    }

    @Test
    void supportsMissingHostAndNumericCompletionTime() {
        when(repository.incidentCount(FROM, TO)).thenReturn(1);
        UUID id = UUID.randomUUID();
        when(repository.labels(FROM, TO)).thenReturn(List.of(
                new FaultLabel("s", "f", "failure", "db", FROM, FROM.plusSeconds(30))));
        when(repository.incidents(FROM, TO)).thenReturn(List.of(
                new EvaluationRunRepository.IncidentRow(id, FROM.plusSeconds(10), "DONE", true,
                        "1767225620.000000000", "[\"db\"]")));
        when(repository.problems(FROM, TO)).thenReturn(List.of(
                new EvaluationRunRepository.ProblemRow(id, FROM.plusSeconds(10), "[null]")));
        var run = service.create(FROM, TO);
        assertThat(run.report().rows().get(0).baselineSource()).isNull();
        assertThat(run.report().rows().get(0).matchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(run.incidents().get(0).completedAt()).isEqualTo(FROM.plusSeconds(20));
    }

    @Test
    void ambiguousIntegerCompletionTimesRemainNullInJson() throws Exception {
        when(repository.incidentCount(FROM, TO)).thenReturn(2);
        UUID id = UUID.randomUUID();
        when(repository.incidents(FROM, TO)).thenReturn(List.of(
                new EvaluationRunRepository.IncidentRow(id, FROM, "DONE", true, "1767225620", "[]"),
                new EvaluationRunRepository.IncidentRow(UUID.randomUUID(), FROM, "DONE", true, "1767225620000", "[]")));
        EvaluationRun run = service.create(FROM, TO);
        assertThat(run.incidents()).allMatch(i -> i.completedAt() == null);
        var payload = json.readTree(json.writeValueAsString(run));
        assertThat(payload.path("incidents").get(0).path("completedAt").isNull()).isTrue();
        assertThat(payload.path("incidents").get(1).path("completedAt").isNull()).isTrue();
    }

    @Test
    void rejectsOversizedInputsBeforeEvaluatingOrLoadingProblems() {
        QualityEvaluator evaluator = mock(QualityEvaluator.class);
        EvaluationRunService limited = new EvaluationRunService(repository, evaluator, json);
        FaultLabel label = new FaultLabel("s", "f", "t", "host", FROM, TO);
        when(repository.labels(FROM, TO)).thenReturn(java.util.Collections.nCopies(EvaluationRunService.MAX_LABELS + 1, label));
        assertThatThrownBy(() -> limited.create(FROM, TO)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("меток");
        when(repository.labels(FROM, TO)).thenReturn(List.of(label));
        when(repository.incidentCount(FROM, TO)).thenReturn(EvaluationRunService.MAX_INCIDENTS + 1);
        assertThatThrownBy(() -> limited.create(FROM, TO)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("инцидентов");
        when(repository.labels(FROM, TO)).thenReturn(java.util.Collections.nCopies(101, label));
        when(repository.incidentCount(FROM, TO)).thenReturn(1000);
        assertThatThrownBy(() -> limited.create(FROM, TO)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("пар");
        verifyNoInteractions(evaluator);
        verify(repository, never()).incidents(any(), any());
        verify(repository, never()).problems(any(), any());
        verify(repository, never()).insert(any());
    }

    @Test
    void boundedSerializerRejectsOversizedJsonWithoutWriting() throws Exception {
        EvaluationRunRepository real = new EvaluationRunRepository(null, json);
        EvaluationRun run = oversizedRun();
        assertThatThrownBy(() -> real.serializeBounded(run)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("JSON снимка");
        EvaluationRun small = new EvaluationRun(UUID.randomUUID(), FROM, FROM, TO, 1, "v", "scope",
                List.of(), List.of(), null);
        assertThat(json.readTree(real.serializeBounded(small)).get("id").asText()).isEqualTo(small.id().toString());
    }

    @Test
    void insertPreservesOversizedSnapshotValidationWithoutUpdating() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class, org.mockito.Mockito.RETURNS_SELF);
        when(jdbc.sql(any(String.class))).thenReturn(statement);
        EvaluationRunRepository real = new EvaluationRunRepository(jdbc, json);

        assertThatThrownBy(() -> real.insert(oversizedRun()))
                .isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("JSON снимка")
                .hasMessageContaining(Integer.toString(EvaluationRunService.MAX_SNAPSHOT_JSON_BYTES));
        verify(statement, never()).update();
    }

    @Test
    void wrappedValidationIsPreservedAndUnrelatedIoRemainsInternal() {
        EvaluationRunService.Validation validation = new EvaluationRunService.Validation("nested validation");
        IOException wrapped = new IOException("outer", new IOException("inner", validation));
        EvaluationRunRepository validating = new EvaluationRunRepository(null, mapperThrowing(wrapped));
        EvaluationRun small = new EvaluationRun(UUID.randomUUID(), FROM, FROM, TO, 1, "v", "scope",
                List.of(new FaultLabel("s", "f", "t", "host", FROM, TO)), List.of(), null);
        assertThatThrownBy(() -> validating.serializeBounded(small)).isSameAs(validation);

        IOException unrelated = new IOException("unrelated I/O failure");
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class, org.mockito.Mockito.RETURNS_SELF);
        when(jdbc.sql(any(String.class))).thenReturn(statement);
        EvaluationRunRepository failing = new EvaluationRunRepository(jdbc, mapperThrowing(unrelated));
        assertThatThrownBy(() -> failing.insert(small)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot serialize evaluation snapshot").hasRootCause(unrelated);
        verify(statement, never()).update();
    }

    private static EvaluationRun oversizedRun() {
        String chunk = "x".repeat(1024);
        FaultLabel label = new FaultLabel("s", "f", "t", chunk, FROM, TO);
        int count = EvaluationRunService.MAX_SNAPSHOT_JSON_BYTES / chunk.length() + 1;
        return new EvaluationRun(UUID.randomUUID(), FROM, FROM, TO, 1, "v", "scope",
                java.util.Collections.nCopies(count, label), List.of(), null);
    }

    private static ObjectMapper mapperThrowing(IOException failure) {
        SimpleModule module = new SimpleModule();
        module.addSerializer(FaultLabel.class, new JsonSerializer<>() {
            @Override
            public void serialize(FaultLabel value, JsonGenerator generator, SerializerProvider serializers)
                    throws IOException {
                throw failure;
            }
        });
        return new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(module);
    }

    @Test
    void repositoryLimitFailureStopsEvaluationAndPersistence() {
        QualityEvaluator evaluator = mock(QualityEvaluator.class);
        EvaluationRunService limited = new EvaluationRunService(repository, evaluator, json);
        when(repository.problems(FROM, TO)).thenThrow(new EvaluationRunService.Validation("Слишком много hosts для окна"));
        assertThatThrownBy(() -> limited.create(FROM, TO)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("hosts");
        verifyNoInteractions(evaluator);
        verify(repository, never()).insert(any());
    }

    @Test
    void jacksonAndConstructorsRejectNonObjectHostElements() throws Exception {
        for (String bad : List.of("[null]", "[1]", "[true]", "[\"SECRET_VALUE\"]", "[[]]",
                "[{\"host\":\"ok\"},null]")) {
            assertThatThrownBy(() -> {
                var hosts = json.readValue(bad, new com.fasterxml.jackson.core.type.TypeReference<List<ru.itmo.advisor.domain.model.HostRef>>() { });
                new ru.itmo.advisor.domain.model.Problem(1, "test", 1, hosts, List.of(), FROM, null);
            }).isInstanceOf(Exception.class);
        }
        var valid = json.readValue("[{}, {\"host\":null}, {\"host\":123,\"name\":true}]",
                new com.fasterxml.jackson.core.type.TypeReference<List<ru.itmo.advisor.domain.model.HostRef>>() { });
        assertThat(new ru.itmo.advisor.domain.model.Problem(1, "test", 1, valid, List.of(), FROM, null).hosts())
                .extracting(ru.itmo.advisor.domain.model.HostRef::host).containsExactly(null, null, "123");
    }

    @Test
    void nullAndEmptyHostsFollowIncidentAndProblemFallbackSemantics() throws Exception {
        EvaluationRunRepository real = new EvaluationRunRepository(null, json);
        var hostType = new com.fasterxml.jackson.core.type.TypeReference<List<ru.itmo.advisor.domain.model.HostRef>>() { };
        for (String value : List.of("null", "[]")) {
            var hosts = json.readValue(value, hostType);
            real.validateSourceRow(value, "[]", null, true);
            real.validateSourceRow(value, "[]", null, false);
            var fallback = new ru.itmo.advisor.domain.model.Incident(UUID.randomUUID(), 1, "test", 1,
                    hosts, List.of(), FROM, null, ru.itmo.advisor.domain.model.IncidentStatus.OPEN,
                    ru.itmo.advisor.domain.model.AnalysisStatus.PENDING, null, null);
            assertThat(fallback.hosts()).isEmpty();
            assertThat(fallback.problems()).singleElement().satisfies(p -> assertThat(p.hosts()).isEmpty());
            var first = new ru.itmo.advisor.domain.model.Problem(2, "test", 1, hosts, List.of(), FROM, null);
            var incident = new ru.itmo.advisor.domain.model.Incident(UUID.randomUUID(), 1, "test", 1,
                    List.of(new ru.itmo.advisor.domain.model.HostRef("incident-host", null)), List.of(), FROM, null,
                    ru.itmo.advisor.domain.model.IncidentStatus.OPEN, ru.itmo.advisor.domain.model.AnalysisStatus.PENDING,
                    null, null, List.of(first));
            assertThat(incident.problems()).singleElement().satisfies(p -> assertThat(p.hosts()).isEmpty());
        }
        for (String bad : List.of("{}", "42", "\"wrong\"", "true")) {
            for (boolean incident : new boolean[] {true, false}) {
                assertThatThrownBy(() -> real.validateSourceRow(bad, "[]", null, incident))
                        .isInstanceOf(EvaluationRunService.Validation.class).hasMessageContaining("hosts");
            }
        }
    }

    @Test
    void emptyProjectedHostsRemainEmptyInSnapshotAndEvaluator() {
        UUID id = UUID.randomUUID();
        when(repository.incidentCount(FROM, TO)).thenReturn(1);
        when(repository.incidents(FROM, TO)).thenReturn(List.of(
                new EvaluationRunRepository.IncidentRow(id, FROM, "PENDING", false, null, "[]")));
        when(repository.problems(FROM, TO)).thenReturn(List.of(
                new EvaluationRunRepository.ProblemRow(id, FROM, "[]")));
        QualityEvaluator evaluator = mock(QualityEvaluator.class);
        EvaluationRun run = new EvaluationRunService(repository, evaluator, json).create(FROM, TO);
        assertThat(run.incidents().get(0).problems()).singleElement()
                .satisfies(p -> assertThat(p.hosts()).isEmpty());
        @SuppressWarnings("unchecked")
        var incidents = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(evaluator).evaluate(any(), incidents.capture(), any());
        assertThat((ru.itmo.advisor.domain.model.Incident) incidents.getValue().get(0))
                .satisfies(i -> assertThat(i.problems().get(0).hosts()).isEmpty());
    }

    @Test
    void invalidHostElementStopsEvaluationAndPersistence() {
        QualityEvaluator evaluator = mock(QualityEvaluator.class);
        EvaluationRunService limited = new EvaluationRunService(repository, evaluator, json);
        when(repository.problems(FROM, TO)).thenThrow(
                new EvaluationRunService.Validation("Повреждённые данные: некорректный элемент hosts"));
        assertThatThrownBy(() -> limited.create(FROM, TO)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessage("Повреждённые данные: некорректный элемент hosts");
        verifyNoInteractions(evaluator);
        verify(repository, never()).insert(any());
    }

    @Test
    void corruptRepositoryInputsStopEvaluationAndPersistence() {
        QualityEvaluator evaluator = mock(QualityEvaluator.class);
        EvaluationRunService limited = new EvaluationRunService(repository, evaluator, json);
        when(repository.incidentCount(FROM, TO)).thenReturn(1);
        when(repository.incidents(FROM, TO)).thenThrow(new EvaluationRunService.Validation("Повреждённые данные: analysis.suspects должен быть массивом"));
        assertThatThrownBy(() -> limited.create(FROM, TO)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("analysis.suspects");
        verify(repository, never()).problems(any(), any());
        verifyNoInteractions(evaluator);
        verify(repository, never()).insert(any());
    }

    @Test
    void acceptsLimitBoundaries() {
        QualityEvaluator evaluator = mock(QualityEvaluator.class);
        EvaluationRunService limited = new EvaluationRunService(repository, evaluator, json);
        FaultLabel label = new FaultLabel("s", "f", "t", "host", FROM, TO);
        when(repository.labels(FROM, TO)).thenReturn(java.util.Collections.nCopies(EvaluationRunService.MAX_LABELS, label));
        when(repository.incidentCount(FROM, TO)).thenReturn(100);
        when(repository.incidents(FROM, TO)).thenReturn(java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> new EvaluationRunRepository.IncidentRow(UUID.randomUUID(), FROM, "PENDING", false, null, "[]"))
                .toList());
        limited.create(FROM, TO);
        verify(evaluator).evaluate(any(), any(), any());
        verify(repository).insert(any());
    }

    @Test
    void incidentCountLimitIsInclusive() {
        when(repository.incidentCount(FROM, TO)).thenReturn(EvaluationRunService.MAX_INCIDENTS);
        QualityEvaluator evaluator = mock(QualityEvaluator.class);
        EvaluationRunService limited = new EvaluationRunService(repository, evaluator, json);
        when(repository.incidents(FROM, TO)).thenReturn(java.util.stream.IntStream.range(0, EvaluationRunService.MAX_INCIDENTS)
                .mapToObj(i -> new EvaluationRunRepository.IncidentRow(UUID.randomUUID(), FROM, "PENDING", false, null, "[]"))
                .toList());
        limited.create(FROM, TO);
        verify(evaluator).evaluate(any(), any(), any());
        verify(repository).insert(any());
    }

    @Test
    void validatesDateBoundariesBeforeReading() {
        Instant min = EvaluationRunService.MIN_DATE;
        Instant max = EvaluationRunService.MAX_DATE;
        assertThatThrownBy(() -> service.create(min.minusNanos(1000), min.plusSeconds(1)))
                .isInstanceOf(EvaluationRunService.Validation.class);
        assertThatThrownBy(() -> service.create(max.minusSeconds(1), max.plusNanos(1000)))
                .isInstanceOf(EvaluationRunService.Validation.class);
        verifyNoInteractions(repository);
        assertThat(service.create(min, min.plusSeconds(1)).from()).isEqualTo(min);
        assertThat(service.create(max.minusSeconds(1), max).to()).isEqualTo(max);
    }

    @Test
    void validatesWindowBeforeReadingOrWriting() {
        assertThatThrownBy(() -> service.create(null, TO)).isInstanceOf(EvaluationRunService.Validation.class);
        assertThatThrownBy(() -> service.create(FROM, FROM)).isInstanceOf(EvaluationRunService.Validation.class);
        assertThatThrownBy(() -> service.create(TO, FROM)).isInstanceOf(EvaluationRunService.Validation.class);
        assertThatThrownBy(() -> service.create(FROM, FROM.plusSeconds(31L * 86400 + 1)))
                .isInstanceOf(EvaluationRunService.Validation.class);
        assertThatThrownBy(() -> service.create(FROM.plusNanos(1), TO)).isInstanceOf(EvaluationRunService.Validation.class);
        assertThatThrownBy(() -> service.create(Instant.MIN, TO)).isInstanceOf(EvaluationRunService.Validation.class);
        assertThatThrownBy(() -> service.create(FROM, Instant.MAX)).isInstanceOf(EvaluationRunService.Validation.class);
        verify(repository, never()).labels(any(), any());
        verify(repository, never()).insert(any());
    }

    @Test
    void acceptsExactly31Days() {
        Instant end = FROM.plusSeconds(31L * 86400);
        when(repository.labels(FROM, end)).thenReturn(List.of());
        when(repository.incidents(FROM, end)).thenReturn(List.of());
        when(repository.problems(FROM, end)).thenReturn(List.of());
        assertThat(service.create(FROM, end).to()).isEqualTo(end);
    }

    @Test
    void fullSourceValidationRejectsMalformedFieldsWithoutLeakingSecrets() {
        EvaluationRunRepository real = new EvaluationRunRepository(null, json);
        for (String analysis : List.of(
                "{\"suspects\":[{\"host\":\"db\",\"score\":\"SECRET_SCORE\"}]}",
                "{\"suspects\":[{\"host\":\"db\",\"reasons\":[{\"SECRET_REASON\":1}]}]}",
                "{\"suspects\":[{\"host\":\"db\",\"firstDeviationAt\":\"SECRET_TIME\"}]}",
                "{\"scope\":{\"SECRET_SCOPE\":1}}",
                "{\"analyzedAt\":\"SECRET_TIME\"}",
                "{\"completedAt\":\"SECRET_TIME\"}")) {
            assertThatThrownBy(() -> real.validateSourceRow("[]", "[]", analysis, true))
                    .isInstanceOf(EvaluationRunService.Validation.class)
                    .hasMessageNotContaining("SECRET").hasNoCause();
        }
        for (boolean incident : new boolean[] {true, false}) {
            assertThatThrownBy(() -> real.validateSourceRow("[{\"host\":\"db\",\"name\":{\"SECRET\":1}}]", "[]", null, incident))
                    .isInstanceOf(EvaluationRunService.Validation.class).hasMessageContaining("hosts")
                    .hasMessageNotContaining("SECRET").hasNoCause();
            assertThatThrownBy(() -> real.validateSourceRow("[]", "[{\"tag\":{\"SECRET\":1}}]", null, incident))
                    .isInstanceOf(EvaluationRunService.Validation.class).hasMessageContaining("tags")
                    .hasMessageNotContaining("SECRET").hasNoCause();
        }
    }

    @Test
    void sourceValidationUsesInjectedMapperAndPreservesJacksonCoercions() {
        EvaluationRunRepository real = new EvaluationRunRepository(null, json);
        real.validateSourceRow("[{\"host\":123,\"name\":true}]", "[]",
                "{\"scope\":[123,true],\"suspects\":[{\"host\":123,\"score\":\"1.25\",\"reasons\":[true]}]}", true);
        ObjectMapper strict = json.copy().disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
        EvaluationRunRepository strictRepository = new EvaluationRunRepository(null, strict);
        assertThatThrownBy(() -> strictRepository.validateSourceRow("[]", "[]",
                "{\"suspects\":[{\"score\":\"1.25\"}]}", true))
                .isInstanceOf(EvaluationRunService.Validation.class).hasNoCause();
    }

    @Test
    void sourceBudgetChecksAllBoundaries() {
        EvaluationRunRepository.checkSourceBudget(EvaluationRunService.MAX_INCIDENTS, EvaluationRunService.MAX_PROBLEMS,
                EvaluationRunService.MAX_PROBLEMS_PER_INCIDENT, EvaluationRunService.MAX_INPUT_JSON_BYTES);
        assertThatThrownBy(() -> EvaluationRunRepository.checkSourceBudget(EvaluationRunService.MAX_INCIDENTS + 1, 0, 0, 0))
                .isInstanceOf(EvaluationRunService.Validation.class).hasMessageContaining("инцидентов");
        assertThatThrownBy(() -> EvaluationRunRepository.checkSourceBudget(1, EvaluationRunService.MAX_PROBLEMS + 1, 0, 0))
                .isInstanceOf(EvaluationRunService.Validation.class).hasMessageContaining("problems для окна");
        assertThatThrownBy(() -> EvaluationRunRepository.checkSourceBudget(1, 101, 101, 0))
                .isInstanceOf(EvaluationRunService.Validation.class).hasMessageContaining("problems в инциденте");
        assertThatThrownBy(() -> EvaluationRunRepository.checkSourceBudget(1, 0, 0, EvaluationRunService.MAX_INPUT_JSON_BYTES + 1L))
                .isInstanceOf(EvaluationRunService.Validation.class).hasMessageContaining("JSON входных данных");
    }

    @Test
    void validatesBudgetAndCompleteSourceBeforeProjectionOrEvaluation() {
        QualityEvaluator evaluator = mock(QualityEvaluator.class);
        EvaluationRunService limited = new EvaluationRunService(repository, evaluator, json);
        org.mockito.Mockito.doThrow(new EvaluationRunService.Validation("Повреждённые данные"))
                .when(repository).validateSourceJson(FROM, TO);
        assertThatThrownBy(() -> limited.create(FROM, TO)).isInstanceOf(EvaluationRunService.Validation.class);
        var order = org.mockito.Mockito.inOrder(repository);
        order.verify(repository).validateSourceBudget(FROM, TO);
        order.verify(repository).validateSourceJson(FROM, TO);
        verify(repository, never()).incidents(any(), any());
        verify(repository, never()).problems(any(), any());
        verify(repository, never()).insert(any());
        verifyNoInteractions(evaluator);
    }

    @Test
    void createRequiresRepeatableRead() throws Exception {
        Transactional tx = EvaluationRunService.class.getMethod("create", Instant.class, Instant.class)
                .getAnnotation(Transactional.class);
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
