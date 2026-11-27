package ru.itmo.advisor.app.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"advisor.incident.catch-up-interval=0", "advisor.zabbix.token=test",
        "advisor.security.ui-token=ui"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class EvaluationRunIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    private final List<UUID> runIds = new ArrayList<>();
    private final List<UUID> incidentIds = new ArrayList<>();
    private final List<String> labelIds = new ArrayList<>();

    private UUID newIncidentId() {
        return UUID.randomUUID();
    }

    private String newLabelId() {
        return UUID.randomUUID().toString();
    }

    private ResultActions createRun(String body) throws Exception {
        ResultActions result = mvc.perform(post("/api/evaluation/runs").header("X-Advisor-Token", "ui")
                .contentType(MediaType.APPLICATION_JSON).content(body));
        var response = result.andReturn().getResponse();
        if (response.getStatus() == 201) {
            runIds.add(UUID.fromString(json.readTree(response.getContentAsString()).required("id").asText()));
        }
        return result;
    }

    private void deleteRuns() {
        for (UUID id : runIds) {
            jdbc.sql("DELETE FROM evaluation_run WHERE id = :id").param("id", id).update();
        }
        runIds.clear();
    }

    @AfterEach
    void cleanup() {
        deleteRuns();
        for (String id : labelIds) {
            jdbc.sql("DELETE FROM fault_label WHERE fault_id = :id").param("id", id).update();
        }
        for (UUID id : incidentIds) {
            jdbc.sql("DELETE FROM incident WHERE id = :id").param("id", id).update();
        }
    }

    @Test
    void rejectsMalformedArraysWithoutCreatingSnapshot() throws Exception {
        Instant from = Instant.parse("2085-01-01T00:00:00Z");
        Instant to = from.plusSeconds(3600);
        UUID id = newIncidentId();
        EvaluationRunRepository repository = new EvaluationRunRepository(jdbc, json);
        String body = "{\"from\":\"2085-01-01T00:00:00Z\",\"to\":\"2085-01-01T01:00:00Z\"}";
        jdbc.sql("""
                INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, started_at, status, analysis_status)
                VALUES (:id, 850000000, 'test', 1, '[]', :start, 'OPEN', 'DONE')
                """).param("id", id).param("start", Timestamp.from(from)).update();
        incidentIds.add(id);
        try {
            for (String bad : java.util.List.of("{}", "42", "\"wrong\"", "true")) {
                jdbc.sql("UPDATE incident SET analysis = jsonb_build_object('suspects', CAST(:bad AS jsonb)) WHERE id = :id")
                        .param("bad", bad).param("id", id).update();
                assertThatThrownBy(() -> repository.incidents(from, to))
                        .isInstanceOf(EvaluationRunService.Validation.class).hasMessageContaining("analysis.suspects");
                createRun(body)
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", org.hamcrest.Matchers.containsString("analysis.suspects")));
            }
            for (String valid : java.util.List.of("{}", "{\"suspects\":null}", "{\"suspects\":[]}")) {
                jdbc.sql("UPDATE incident SET analysis = CAST(:value AS jsonb) WHERE id = :id")
                        .param("value", valid).param("id", id).update();
                assertThat(repository.incidents(from, to).get(0).rankedSourcesJson()).isEqualTo("[]");
            }
            jdbc.sql("UPDATE incident SET analysis = NULL WHERE id = :id").param("id", id).update();
            for (String bad : java.util.List.of("{}", "42", "\"wrong\"", "true")) {
                jdbc.sql("UPDATE incident SET hosts = CAST(:bad AS jsonb) WHERE id = :id")
                        .param("bad", bad).param("id", id).update();
                assertThatThrownBy(() -> repository.problems(from, to))
                        .isInstanceOf(EvaluationRunService.Validation.class).hasMessageContaining("hosts");
                createRun(body)
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", org.hamcrest.Matchers.containsString("hosts")));
            }
            jdbc.sql("UPDATE incident SET hosts = '[]' WHERE id = :id").param("id", id).update();
            jdbc.sql("""
                    INSERT INTO incident_problem (event_id, incident_id, trigger_name, severity, hosts, tags, started_at)
                    VALUES (850000001, :id, 'test', 1, '{}', '[]', :start)
                    """).param("id", id).param("start", Timestamp.from(from)).update();
            assertThatThrownBy(() -> repository.problems(from, to))
                    .isInstanceOf(EvaluationRunService.Validation.class).hasMessageContaining("hosts");
            createRun(body)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", org.hamcrest.Matchers.containsString("hosts")));
            assertThat(jdbc.sql("SELECT count(*) FROM evaluation_run WHERE window_from = :start")
                    .param("start", Timestamp.from(from)).query(Integer.class).single()).isZero();
        } finally {
            jdbc.sql("DELETE FROM incident WHERE id = :id").param("id", id).update();
            incidentIds.remove(id);
        }
    }

    @Test
    void normalizesNullHostsInBothSourcesWithoutChangingFallback() throws Exception {
        Instant from = Instant.parse("2090-01-01T00:00:00Z");
        Instant to = from.plusSeconds(3600);
        UUID id = newIncidentId();
        String body = "{\"from\":\"2090-01-01T00:00:00Z\",\"to\":\"2090-01-01T01:00:00Z\"}";
        EvaluationRunRepository repository = new EvaluationRunRepository(jdbc, json);
        jdbc.sql("""
                INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, tags, started_at, status, analysis_status)
                VALUES (:id, 890100000, 'test', 1, '[]', '[]', :start, 'OPEN', 'PENDING')
                """).param("id", id).param("start", Timestamp.from(from)).update();
        incidentIds.add(id);
        try {
            for (boolean problem : new boolean[] {false, true}) {
                if (problem) {
                    jdbc.sql("UPDATE incident SET hosts = '[{\"host\":\"incident-host\"}]' WHERE id = :id")
                            .param("id", id).update();
                    jdbc.sql("""
                            INSERT INTO incident_problem (event_id, incident_id, trigger_name, severity, hosts, tags, started_at)
                            VALUES (890100001, :id, 'first', 1, '[]', '[]', :start),
                                   (890100002, :id, 'later', 1, '[{\"host\":\"later-host\"}]', '[]', :later)
                            """).param("id", id).param("start", Timestamp.from(from))
                            .param("later", Timestamp.from(from.plusSeconds(1))).update();
                }
                String update = problem
                        ? "UPDATE incident_problem SET hosts = CAST(:value AS jsonb) WHERE event_id = 890100001 AND incident_id = :id"
                        : "UPDATE incident SET hosts = CAST(:value AS jsonb) WHERE id = :id";
                for (String value : java.util.List.of("null", "[]")) {
                    var statement = jdbc.sql(update).param("value", value);
                    statement.param("id", id);
                    statement.update();
                    assertThat(repository.problems(from, to)).singleElement().satisfies(row -> {
                        assertThat(row.startedAt()).isEqualTo(from);
                        assertThat(row.hostsJson()).isEqualTo("[]");
                    });
                    createRun(body)
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.incidents[0].problems.length()").value(1))
                            .andExpect(jsonPath("$.incidents[0].problems[0].hosts.length()").value(0));
                }
                for (String bad : java.util.List.of("{}", "42", "\"wrong\"", "true")) {
                    var statement = jdbc.sql(update).param("value", bad);
                    statement.param("id", id);
                    statement.update();
                    assertThatThrownBy(() -> repository.problems(from, to))
                            .isInstanceOf(EvaluationRunService.Validation.class).hasMessageContaining("hosts");
                    createRun(body)
                            .andExpect(status().isBadRequest());
                }
                assertThat(jdbc.sql("SELECT count(*) FROM evaluation_run WHERE window_from = :start")
                        .param("start", Timestamp.from(from)).query(Integer.class).single()).isEqualTo(2);
                deleteRuns();
            }
        } finally {
            deleteRuns();
            jdbc.sql("DELETE FROM incident WHERE id = :id").param("id", id).update();
            incidentIds.remove(id);
        }
    }

    @Test
    void rejectsMalformedSuspectElementsWithoutPersistingAndPreservesValidHosts() throws Exception {
        Instant from = Instant.parse("2086-01-01T00:00:00Z");
        Instant to = from.plusSeconds(3600);
        UUID id = newIncidentId();
        String body = "{\"from\":\"2086-01-01T00:00:00Z\",\"to\":\"2086-01-01T01:00:00Z\"}";
        EvaluationRunRepository repository = new EvaluationRunRepository(jdbc, json);
        jdbc.sql("""
                INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, started_at, status, analysis_status)
                VALUES (:id, 860000000, 'test', 1, '[]', :start, 'OPEN', 'DONE')
                """).param("id", id).param("start", Timestamp.from(from)).update();
        incidentIds.add(id);
        try {
            for (String bad : java.util.List.of(
                    "1", "[]", "\"SECRET_VALUE\"", "{\"suspects\":[null]}", "{\"suspects\":[1]}",
                    "{\"suspects\":[true]}", "{\"suspects\":[\"SECRET_VALUE\"]}", "{\"suspects\":[[]]}",
                    "{\"suspects\":[{\"host\":{\"SECRET_VALUE\":1}}]}", "{\"suspects\":[{\"host\":[]}]}",
                    "{\"suspects\":[{\"host\":\"ok\"},null]}")) {
                jdbc.sql("UPDATE incident SET analysis = CAST(:value AS jsonb) WHERE id = :id")
                        .param("value", bad).param("id", id).update();
                assertThatThrownBy(() -> repository.incidents(from, to))
                        .isInstanceOf(EvaluationRunService.Validation.class).hasMessageNotContaining("SECRET_VALUE");
                String response = createRun(body)
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString())
                        .andReturn().getResponse().getContentAsString();
                assertThat(response).doesNotContain("SECRET_VALUE");
            }
            assertThat(jdbc.sql("SELECT count(*) FROM evaluation_run WHERE window_from = :start")
                    .param("start", Timestamp.from(from)).query(Integer.class).single()).isZero();
            String valid = "{\"suspects\":[{}, {\"host\":null}, {\"host\":123}, {\"host\":true},"
                    + "{\"host\":\"\"}, {\"host\":\"db\",\"score\":1.25,\"firstDeviationAt\":\"2024-01-01T00:00:00Z\","
                    + "\"reasons\":[\"reason\"]}]}";
            jdbc.sql("UPDATE incident SET analysis = CAST(:value AS jsonb) WHERE id = :id")
                    .param("value", valid).param("id", id).update();
            var mapped = json.readValue(valid, ru.itmo.advisor.domain.model.AnalysisResult.class);
            var projected = json.readValue(repository.incidents(from, to).get(0).rankedSourcesJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() { });
            assertThat(projected).containsExactlyElementsOf(mapped.suspects().stream()
                    .map(ru.itmo.advisor.domain.model.Suspect::host).toList());
            createRun(body)
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.incidents[0].rankedSources.length()").value(6));
        } finally {
            deleteRuns();
            jdbc.sql("DELETE FROM incident WHERE id = :id").param("id", id).update();
            incidentIds.remove(id);
        }
    }

    @Test
    void rejectsNonObjectHostElementsInBothSourcesWithoutPersisting() throws Exception {
        Instant from = Instant.parse("2087-01-01T00:00:00Z");
        Instant to = from.plusSeconds(3600);
        UUID id = newIncidentId();
        String body = "{\"from\":\"2087-01-01T00:00:00Z\",\"to\":\"2087-01-01T01:00:00Z\"}";
        EvaluationRunRepository repository = new EvaluationRunRepository(jdbc, json);
        jdbc.sql("""
                INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, started_at, status, analysis_status)
                VALUES (:id, 870000000, 'test', 1, '[]', :start, 'OPEN', 'PENDING')
                """).param("id", id).param("start", Timestamp.from(from)).update();
        incidentIds.add(id);
        try {
            for (boolean problem : new boolean[] {false, true}) {
                if (problem) {
                    jdbc.sql("""
                            INSERT INTO incident_problem (event_id, incident_id, trigger_name, severity, hosts, tags, started_at)
                            VALUES (870000001, :id, 'test', 1, '[]', '[]', :start)
                            """).param("id", id).param("start", Timestamp.from(from)).update();
                }
                String update = problem
                        ? "UPDATE incident_problem SET hosts = CAST(:value AS jsonb) WHERE event_id = 870000001 AND incident_id = :id"
                        : "UPDATE incident SET hosts = CAST(:value AS jsonb) WHERE id = :id";
                for (String bad : java.util.List.of("[null]", "[1]", "[true]", "[\"SECRET_VALUE\"]", "[[]]",
                        "[{\"host\":\"ok\"},null]", "[null,{\"host\":\"ok\"}]")) {
                    var statement = jdbc.sql(update).param("value", bad);
                    statement.param("id", id);
                    statement.update();
                    assertThatThrownBy(() -> repository.problems(from, to))
                            .isInstanceOf(EvaluationRunService.Validation.class)
                            .hasMessage("Повреждённые данные: некорректный элемент hosts")
                            .hasMessageNotContaining("SECRET_VALUE");
                    String response = createRun(body)
                            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString())
                            .andReturn().getResponse().getContentAsString();
                    assertThat(response).contains("hosts").doesNotContain("SECRET_VALUE");
                }
                assertThat(jdbc.sql("SELECT count(*) FROM evaluation_run WHERE window_from = :start")
                        .param("start", Timestamp.from(from)).query(Integer.class).single()).isZero();
                String valid = "[{}, {\"host\":null,\"name\":null}, {\"host\":123,\"name\":true},"
                        + "{\"host\":true,\"name\":123}, {\"host\":\"\",\"name\":\"visible\"}, {\"host\":\"db\"}]";
                var statement = jdbc.sql(update).param("value", valid);
                statement.param("id", id);
                statement.update();
                var mapped = json.readValue(valid, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<ru.itmo.advisor.domain.model.HostRef>>() { });
                var projected = json.readValue(repository.problems(from, to).get(0).hostsJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() { });
                assertThat(projected).containsExactlyElementsOf(mapped.stream().map(ru.itmo.advisor.domain.model.HostRef::host).toList());
                createRun(body)
                        .andExpect(status().isCreated());
                deleteRuns();
            }
        } finally {
            deleteRuns();
            jdbc.sql("DELETE FROM incident WHERE id = :id").param("id", id).update();
            incidentIds.remove(id);
        }
    }

    @Test
    void limitsSqlResultsToOneBeyondEachMaximum() {
        Instant from = Instant.parse("2080-01-01T00:00:00Z");
        Instant to = from.plusSeconds(3600);
        for (int n = 1; n <= EvaluationRunService.MAX_LABELS + 2; n++) {
            String labelId = newLabelId();
            jdbc.sql("""
                    INSERT INTO fault_label (fault_id, scenario_id, type, source, started_at, ended_at)
                    VALUES (:id, 'limit-test', 'test', 'db', :start, :start)
                    """).param("id", labelId).param("start", Timestamp.from(from)).update();
            labelIds.add(labelId);
        }
        for (int n = 1; n <= EvaluationRunService.MAX_INCIDENTS + 2; n++) {
            UUID incidentId = newIncidentId();
            jdbc.sql("""
                    INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, started_at, status, analysis_status)
                    VALUES (:id, :event, 'test', 1, '[]'::jsonb, :start, 'OPEN', 'PENDING')
                    """).param("id", incidentId).param("event", 900000000L + n)
                    .param("start", Timestamp.from(from)).update();
            incidentIds.add(incidentId);
        }
        EvaluationRunRepository repository = new EvaluationRunRepository(jdbc, json);
        assertThat(repository.labels(from, to)).hasSize(EvaluationRunService.MAX_LABELS + 1);
        assertThat(repository.incidentCount(from, to)).isEqualTo(EvaluationRunService.MAX_INCIDENTS + 1);
        assertThat(repository.incidents(from, to)).hasSize(EvaluationRunService.MAX_INCIDENTS + 1);
    }

    @Test
    void boundsFirstProblemsHostsAndSuspectsWithoutTruncation() throws Exception {
        Instant from = Instant.parse("2081-01-01T00:00:00Z");
        Instant to = from.plusSeconds(3600);
        UUID id = newIncidentId();
        jdbc.sql("""
                INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, started_at, status, analysis_status, analysis)
                VALUES (:id, 810000000, 'test', 1, '[]', :start, 'OPEN', 'DONE',
                        jsonb_build_object('suspects', (SELECT jsonb_agg(jsonb_build_object('host', 'h' || n) ORDER BY n)
                            FROM generate_series(1, :count) n)))
                """).param("id", id).param("start", Timestamp.from(from))
                .param("count", EvaluationRunService.MAX_SUSPECTS_PER_INCIDENT).update();
        incidentIds.add(id);
        EvaluationRunRepository repository = new EvaluationRunRepository(jdbc, json);
        assertThat(json.readTree(repository.incidents(from, to).get(0).rankedSourcesJson()))
                .hasSize(EvaluationRunService.MAX_SUSPECTS_PER_INCIDENT);
        jdbc.sql("UPDATE incident SET analysis = jsonb_set(analysis, '{suspects}', analysis->'suspects' || '[{\"host\":\"extra\"}]'::jsonb) WHERE id = :id")
                .param("id", id).update();
        assertThatThrownBy(() -> repository.incidents(from, to)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("suspects");
        String body = "{\"from\":\"2081-01-01T00:00:00Z\",\"to\":\"2081-01-01T01:00:00Z\"}";
        createRun(body)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("suspects")));
        assertThat(jdbc.sql("SELECT count(*) FROM evaluation_run WHERE window_from = :start")
                .param("start", Timestamp.from(from)).query(Integer.class).single()).isZero();
        jdbc.sql("UPDATE incident SET analysis = NULL WHERE id = :id").param("id", id).update();

        jdbc.sql("""
                UPDATE incident SET hosts = (SELECT jsonb_agg(jsonb_build_object('host', 'h' || n) ORDER BY n)
                    FROM generate_series(1, :count) n) WHERE id = :id
                """).param("id", id).param("count", EvaluationRunService.MAX_HOSTS_PER_PROBLEM).update();
        assertThat(json.readTree(repository.problems(from, to).get(0).hostsJson()))
                .hasSize(EvaluationRunService.MAX_HOSTS_PER_PROBLEM);
        jdbc.sql("UPDATE incident SET hosts = hosts || '[{\"host\":\"extra\"}]'::jsonb WHERE id = :id").param("id", id).update();
        assertThatThrownBy(() -> repository.problems(from, to)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("hosts");
        createRun(body)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("hosts")));

        jdbc.sql("""
                INSERT INTO incident_problem (event_id, incident_id, trigger_name, severity, hosts, tags, started_at)
                SELECT 810000000 + n, :id, 'test', 1, '[{\"host\":\"db\"}]', '[]', :start
                FROM generate_series(1, :count) n
                """).param("id", id).param("start", Timestamp.from(from))
                .param("count", EvaluationRunService.MAX_PROBLEMS_PER_INCIDENT).update();
        assertThat(repository.problems(from, to)).hasSize(EvaluationRunService.MAX_PROBLEMS_PER_INCIDENT);
        jdbc.sql("""
                INSERT INTO incident_problem (event_id, incident_id, trigger_name, severity, hosts, tags, started_at)
                VALUES (810001000, :id, 'test', 1, '[]', '[]', :start)
                """).param("id", id).param("start", Timestamp.from(from)).update();
        assertThatThrownBy(() -> repository.problems(from, to)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("problems");
        createRun(body)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("problems")));
        jdbc.sql("DELETE FROM incident WHERE id = :id").param("id", id).update();
        incidentIds.remove(id);
    }

    @Test
    void enforcesWindowTotalsBeforeReturningOrPersisting() throws Exception {
        Instant from = Instant.parse("2082-01-01T00:00:00Z");
        Instant to = from.plusSeconds(3600);
        int incidents = EvaluationRunService.MAX_SUSPECTS / EvaluationRunService.MAX_SUSPECTS_PER_INCIDENT;
        List<UUID> baseIds = new ArrayList<>();
        for (int n = 1; n <= incidents; n++) {
            UUID incidentId = newIncidentId();
            baseIds.add(incidentId);
            jdbc.sql("""
                    INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, started_at, status, analysis_status, analysis)
                    VALUES (:id, :event, 'test', 1, '[]', :start, 'OPEN', 'DONE',
                           jsonb_build_object('suspects', (SELECT jsonb_agg(jsonb_build_object('host', 'h'))
                               FROM generate_series(1, :suspects))))
                    """).param("id", incidentId).param("event", 820000000L + n).param("start", Timestamp.from(from))
                    .param("suspects", EvaluationRunService.MAX_SUSPECTS_PER_INCIDENT).update();
            incidentIds.add(incidentId);
        }
        EvaluationRunRepository repository = new EvaluationRunRepository(jdbc, json);
        assertThat(repository.incidents(from, to)).hasSize(incidents);
        UUID extraId = newIncidentId();
        jdbc.sql("""
                INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, started_at, status, analysis_status, analysis)
                VALUES (:id, 820001000, 'test', 1, '[]', :start, 'OPEN', 'DONE', '{\"suspects\":[{\"host\":\"extra\"}]}')
                """).param("id", extraId).param("start", Timestamp.from(from)).update();
        incidentIds.add(extraId);
        assertThatThrownBy(() -> repository.incidents(from, to)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("suspects для окна");
        String body = "{\"from\":\"2082-01-01T00:00:00Z\",\"to\":\"2082-01-01T01:00:00Z\"}";
        createRun(body)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("suspects для окна")));
        jdbc.sql("DELETE FROM incident WHERE id = :id").param("id", extraId).update();
        incidentIds.remove(extraId);
        for (int i = 0; i < baseIds.size(); i++) {
            jdbc.sql("""
                    INSERT INTO incident_problem (event_id, incident_id, trigger_name, severity, hosts, tags, started_at)
                    SELECT :eventBase + n, :id, 'test', 1,
                           (SELECT jsonb_agg(jsonb_build_object('host', 'h')) FROM generate_series(1, :hosts)), '[]', :start
                    FROM generate_series(1, :problems) n
                    """).param("id", baseIds.get(i)).param("eventBase", 820000000000L + (i + 1L) * 1000)
                    .param("start", Timestamp.from(from)).param("problems", EvaluationRunService.MAX_PROBLEMS_PER_INCIDENT)
                    .param("hosts", EvaluationRunService.MAX_HOSTS / EvaluationRunService.MAX_PROBLEMS).update();
        }
        assertThat(repository.problems(from, to)).hasSize(EvaluationRunService.MAX_PROBLEMS);
        jdbc.sql("""
                UPDATE incident_problem SET hosts = hosts || '[{\"host\":\"extra\"}]'::jsonb
                WHERE event_id = 820000001001 AND incident_id = :id
                """).param("id", baseIds.get(0)).update();
        assertThatThrownBy(() -> repository.problems(from, to)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("hosts для окна");
        jdbc.sql("UPDATE incident_problem SET hosts = hosts - (jsonb_array_length(hosts) - 1) WHERE event_id = 820000001001 AND incident_id = :id")
                .param("id", baseIds.get(0)).update();
        UUID fallbackId = newIncidentId();
        jdbc.sql("""
                INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, started_at, status, analysis_status)
                VALUES (:id, 820001000, 'test', 1, '[]', :start, 'OPEN', 'PENDING')
                """).param("id", fallbackId).param("start", Timestamp.from(from)).update();
        incidentIds.add(fallbackId);
        assertThatThrownBy(() -> repository.problems(from, to)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("problems для окна");
        createRun(body)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("problems для окна")));
        assertThat(jdbc.sql("SELECT count(*) FROM evaluation_run WHERE window_from = :start")
                .param("start", Timestamp.from(from)).query(Integer.class).single()).isZero();
    }

    @Test
    void rejectsOversizedStringsAndJsonBeforeReturningOrPersisting() throws Exception {
        Instant from = Instant.parse("2083-01-01T00:00:00Z");
        Instant to = from.plusSeconds(3600);
        UUID id = newIncidentId();
        EvaluationRunRepository repository = new EvaluationRunRepository(jdbc, json);
        String labelId = newLabelId();
        String oversizedId = UUID.randomUUID() + "x".repeat(EvaluationRunService.MAX_STRING_BYTES);
        jdbc.sql("""
                INSERT INTO fault_label (fault_id, scenario_id, type, source, started_at, ended_at)
                VALUES (:id, 's', 't', repeat('я', :length), :start, :start)
                """).param("id", labelId).param("length", EvaluationRunService.MAX_STRING_BYTES / 2)
                .param("start", Timestamp.from(from)).update();
        labelIds.add(labelId);
        assertThat(repository.labels(from, to).get(0).source()).hasSize(EvaluationRunService.MAX_STRING_BYTES / 2);
        jdbc.sql("UPDATE fault_label SET source = source || 'я' WHERE fault_id = :id").param("id", labelId).update();
        assertThatThrownBy(() -> repository.labels(from, to)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("строка");
        jdbc.sql("UPDATE fault_label SET source = 'db', type = repeat('x', :length) WHERE fault_id = :id")
                .param("id", labelId).param("length", EvaluationRunService.MAX_STRING_BYTES + 1).update();
        assertThatThrownBy(() -> repository.labels(from, to)).isInstanceOf(EvaluationRunService.Validation.class);
        jdbc.sql("UPDATE fault_label SET type = 't', scenario_id = repeat('x', :length) WHERE fault_id = :id")
                .param("id", labelId).param("length", EvaluationRunService.MAX_STRING_BYTES + 1).update();
        assertThatThrownBy(() -> repository.labels(from, to)).isInstanceOf(EvaluationRunService.Validation.class);
        jdbc.sql("UPDATE fault_label SET scenario_id = 's', fault_id = :newId WHERE fault_id = :id")
                .param("id", labelId).param("newId", oversizedId).update();
        labelIds.remove(labelId);
        labelIds.add(oversizedId);
        assertThatThrownBy(() -> repository.labels(from, to)).isInstanceOf(EvaluationRunService.Validation.class);
        jdbc.sql("DELETE FROM fault_label WHERE fault_id = :id").param("id", oversizedId).update();
        labelIds.remove(oversizedId);

        jdbc.sql("""
                INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, started_at, status, analysis_status, analysis)
                VALUES (:id, 830000000, 'test', 1, jsonb_build_array(jsonb_build_object('host', repeat('я', :length))),
                        :start, 'OPEN', 'DONE', jsonb_build_object('suspects', jsonb_build_array(jsonb_build_object('host', repeat('я', :length)))))
                """).param("id", id).param("start", Timestamp.from(from))
                .param("length", EvaluationRunService.MAX_STRING_BYTES / 2).update();
        incidentIds.add(id);
        assertThat(json.readTree(repository.incidents(from, to).get(0).rankedSourcesJson()).get(0).asText())
                .hasSize(EvaluationRunService.MAX_STRING_BYTES / 2);
        assertThat(json.readTree(repository.problems(from, to).get(0).hostsJson()).get(0).asText())
                .hasSize(EvaluationRunService.MAX_STRING_BYTES / 2);
        jdbc.sql("UPDATE incident SET hosts = jsonb_build_array(jsonb_build_object('host', repeat('я', :length))) WHERE id = :id")
                .param("id", id).param("length", EvaluationRunService.MAX_STRING_BYTES / 2 + 1).update();
        assertThatThrownBy(() -> repository.problems(from, to)).isInstanceOf(EvaluationRunService.Validation.class);
        jdbc.sql("UPDATE incident SET analysis = jsonb_build_object('suspects', hosts) WHERE id = :id").param("id", id).update();
        assertThatThrownBy(() -> repository.incidents(from, to)).isInstanceOf(EvaluationRunService.Validation.class);
        String body = "{\"from\":\"2083-01-01T00:00:00Z\",\"to\":\"2083-01-01T01:00:00Z\"}";
        createRun(body).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM evaluation_run WHERE window_from = :start")
                .param("start", Timestamp.from(from)).query(Integer.class).single()).isZero();
        jdbc.sql("DELETE FROM incident WHERE id = :id").param("id", id).update();
        incidentIds.remove(id);
    }

    @Test
    void rejectsAggregateJsonBudgetEvenWhenEveryHostIsWithinLimit() {
        Instant from = Instant.parse("2084-01-01T00:00:00Z");
        Instant to = from.plusSeconds(3600);
        for (int n = 1; n <= 42; n++) {
            UUID incidentId = newIncidentId();
            jdbc.sql("""
                    INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, started_at, status, analysis_status, analysis)
                    VALUES (:id, :event, 'test', 1,
                           (SELECT jsonb_agg(jsonb_build_object('host', repeat('x', :length))) FROM generate_series(1, 100)),
                           :start, 'OPEN', 'DONE',
                           jsonb_build_object('suspects', (SELECT jsonb_agg(jsonb_build_object('host', repeat('x', :length))) FROM generate_series(1, 100))))
                    """).param("id", incidentId).param("event", 840000000L + n)
                    .param("start", Timestamp.from(from)).param("length", EvaluationRunService.MAX_STRING_BYTES).update();
            incidentIds.add(incidentId);
        }
        EvaluationRunRepository repository = new EvaluationRunRepository(jdbc, json);
        assertThatThrownBy(() -> repository.incidents(from, to)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("JSON входных данных");
        assertThatThrownBy(() -> repository.problems(from, to)).isInstanceOf(EvaluationRunService.Validation.class)
                .hasMessageContaining("JSON входных данных");
    }

    @Test
    void sourceBudgetCountsAllProblemsAndAllJsonBytes() {
        Instant from = Instant.parse("2089-01-01T00:00:00Z");
        Instant to = from.plusSeconds(3600);
        UUID id = newIncidentId();
        EvaluationRunRepository repository = new EvaluationRunRepository(jdbc, json);
        jdbc.sql("""
                INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, tags, started_at, status, analysis_status)
                VALUES (:id, 890000000, 'test', 1, '[]', '[]', :start, 'OPEN', 'PENDING')
                """).param("id", id).param("start", Timestamp.from(from)).update();
        incidentIds.add(id);
        try {
            jdbc.sql("""
                    INSERT INTO incident_problem (event_id, incident_id, trigger_name, severity, hosts, tags, started_at)
                    SELECT 890000000 + n, :id, 'test', 1, '[]', '[]', CAST(:start AS timestamptz) + n * interval '1 second'
                    FROM generate_series(1, :count) n
                    """).param("id", id).param("start", Timestamp.from(from))
                    .param("count", EvaluationRunService.MAX_PROBLEMS_PER_INCIDENT + 1).update();
            assertThatThrownBy(() -> repository.validateSourceBudget(from, to))
                    .isInstanceOf(EvaluationRunService.Validation.class).hasMessageContaining("problems в инциденте");
            jdbc.sql("DELETE FROM incident_problem WHERE incident_id = :id AND event_id IN (:events)")
                    .param("id", id).param("events", java.util.stream.LongStream.rangeClosed(
                            890000001L, 890000000L + EvaluationRunService.MAX_PROBLEMS_PER_INCIDENT + 1)
                            .boxed().toList()).update();
            jdbc.sql("UPDATE incident SET analysis = jsonb_build_object('secret', repeat('x', :size)) WHERE id = :id")
                    .param("id", id).param("size", EvaluationRunService.MAX_INPUT_JSON_BYTES).update();
            assertThatThrownBy(() -> repository.validateSourceBudget(from, to))
                    .isInstanceOf(EvaluationRunService.Validation.class).hasMessageContaining("JSON входных данных")
                    .hasMessageNotContaining("secret");
        } finally {
            jdbc.sql("DELETE FROM incident WHERE id = :id").param("id", id).update();
            incidentIds.remove(id);
        }
    }

    @Test
    void validatesLaterProblemsAndFullJsonBeforeSnapshot() throws Exception {
        Instant from = Instant.parse("2088-01-01T00:00:00Z");
        UUID id = newIncidentId();
        String body = "{\"from\":\"2088-01-01T00:00:00Z\",\"to\":\"2088-01-01T01:00:00Z\"}";
        jdbc.sql("""
                INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, tags, started_at, status, analysis_status, analysis)
                VALUES (:id, 880000000, 'test', 1, '[{\"host\":\"db\"}]', '[]', :start, 'OPEN', 'DONE',
                    '{\"suspects\":[{\"host\":\"db\"}]}')
                """).param("id", id).param("start", Timestamp.from(from)).update();
        incidentIds.add(id);
        jdbc.sql("""
                INSERT INTO incident_problem (event_id, incident_id, trigger_name, severity, hosts, tags, started_at)
                VALUES (880000001, :id, 'first', 1, '[{\"host\":\"db\"}]', '[]', :first),
                       (880000002, :id, 'later', 1, '[{\"host\":\"db\",\"name\":{\"SECRET_LATE\":1}}]', '[]', :later)
                """).param("id", id).param("first", Timestamp.from(from))
                .param("later", Timestamp.from(from.plusSeconds(10))).update();
        try {
            String response = createRun(body)
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("SECRET_LATE");
            assertThat(jdbc.sql("SELECT count(*) FROM evaluation_run WHERE window_from = :start")
                    .param("start", Timestamp.from(from)).query(Integer.class).single()).isZero();
            jdbc.sql("UPDATE incident_problem SET hosts = '[{\"host\":\"db\",\"name\":\"SECRET_NAME\"}]' WHERE event_id = 880000002 AND incident_id = :id")
                    .param("id", id).update();
            jdbc.sql("UPDATE incident SET analysis = jsonb_set(analysis, '{suspects,0,score}', '\"SECRET_SCORE\"') WHERE id = :id")
                    .param("id", id).update();
            response = createRun(body)
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("SECRET_SCORE", "SECRET_NAME");
            jdbc.sql("UPDATE incident SET analysis = '{\"suspects\":[{\"host\":\"db\",\"reasons\":[\"SECRET_REASON\"]}]}' WHERE id = :id")
                    .param("id", id).update();
            response = createRun(body)
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("SECRET_REASON", "SECRET_NAME");
            assertThat(jdbc.sql("SELECT payload::text FROM evaluation_run WHERE id = :id")
                    .param("id", runIds.get(runIds.size() - 1)).query(String.class).single())
                    .doesNotContain("SECRET_REASON", "SECRET_NAME");
        } finally {
            deleteRuns();
            jdbc.sql("DELETE FROM incident WHERE id = :id").param("id", id).update();
            incidentIds.remove(id);
        }
    }

    @Test
    void persistsSnapshotIndependentlyOfSources() throws Exception {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        UUID id = newIncidentId();
        String a = newLabelId();
        String b = newLabelId();
        String past = newLabelId();
        String future = newLabelId();
        jdbc.sql("""
                INSERT INTO fault_label (fault_id, scenario_id, type, source, started_at, ended_at)
                VALUES (:a, 's1', 'test', 'db', :start, :end), (:b, 's2', 'test', 'db', :start, :end),
                       (:pastId, 's3', 'test', 'db', :past, :past), (:future, 's4', 'test', 'db', :to, :to)
                """).param("a", a).param("b", b).param("pastId", past).param("future", future)
                .param("start", Timestamp.from(from.minusSeconds(30))).param("end", Timestamp.from(from))
                .param("past", Timestamp.from(from.minusSeconds(121))).param("to", Timestamp.from(from.plusSeconds(3600))).update();
        labelIds.addAll(List.of(a, b, past, future));
        jdbc.sql("""
                INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, started_at, status,
                                      analysis_status, analysis)
                VALUES (:id, 1, 'SECRET_TRIGGER', 1, '[{"host":"db","name":"SECRET_NAME"}]'::jsonb,
                        :start, 'OPEN', 'DONE', CAST(:analysis AS jsonb))
                """).param("id", id).param("start", Timestamp.from(from.plusSeconds(10)))
                .param("analysis", "{\"suspects\":[{\"host\":\"db\",\"reasons\":[\"SECRET_REASON\"]}],"
                        + "\"completedAt\":\"2026-01-01T00:00:20Z\","
                        + "\"logEvidence\":[{\"example\":\"SECRET_LOG\"}],"
                        + "\"explanation\":{\"summary\":\"SECRET_EXPLANATION\"}}").update();
        incidentIds.add(id);
        String body = "{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-01T01:00:00Z\"}";
        String created = createRun(body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.labels.length()").value(2))
                .andExpect(jsonPath("$.report.rows[0].matchStatus").value("AMBIGUOUS"))
                .andReturn().getResponse().getContentAsString();
        assertThat(created).doesNotContain("SECRET_");
        JsonNode snapshot = json.readTree(created);
        String runId = snapshot.get("id").asText();
        for (String labelId : List.of(a, b, past, future)) {
            jdbc.sql("DELETE FROM fault_label WHERE fault_id = :id").param("id", labelId).update();
            labelIds.remove(labelId);
        }
        jdbc.sql("DELETE FROM incident WHERE id = :id").param("id", id).update();
        incidentIds.remove(id);
        String restored = mvc.perform(get("/api/evaluation/runs/" + runId).header("X-Advisor-Token", "ui"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode restoredSnapshot = json.readTree(restored);
        assertThat(restoredSnapshot).isEqualTo(snapshot);
        assertThat(restored).doesNotContain("SECRET_");
        mvc.perform(get("/api/evaluation/runs").header("X-Advisor-Token", "ui"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(runId));
        mvc.perform(get("/api/evaluation/runs/" + runId)).andExpect(status().isUnauthorized());
    }
}
