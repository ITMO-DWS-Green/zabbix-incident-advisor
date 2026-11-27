package ru.itmo.advisor.app.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.itmo.advisor.app.storage.IncidentRepository;
import ru.itmo.advisor.app.storage.LabelRepository;
import ru.itmo.advisor.domain.model.EvaluationReport;

@SpringBootTest(properties = {"advisor.incident.catch-up-interval=0", "advisor.zabbix.token=test",
        "advisor.security.ui-token=ui"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class LiveEvaluationIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired LiveEvaluationService service;
    @SpyBean LabelRepository labels;
    @SpyBean IncidentRepository incidents;

    @BeforeEach
    void clear() {
        jdbc.sql("DELETE FROM fault_label").update();
        jdbc.sql("DELETE FROM incident").update();
        org.mockito.Mockito.clearInvocations(labels, incidents);
    }

    @Test
    void emptyScenarioIgnoresUnrelatedHistory() throws Exception {
        jdbc.sql("""
                INSERT INTO fault_label SELECT 'f' || n, 's', 't', 'x', now(), now()
                FROM generate_series(1, 1001) n
                """).update();
        mvc.perform(get("/api/evaluation").header("X-Advisor-Token", "ui").param("scenarioId", "missing"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.faults").value(0));
        verify(labels, never()).find(null);
        verify(incidents, never()).findAll();
    }

    @Test
    void countsFrequentProblemsAndPairProduct() throws Exception {
        insertIncidents(1);
        jdbc.sql("""
                INSERT INTO incident_problem (event_id, incident_id, trigger_name, severity, hosts, tags, started_at)
                SELECT n, i.id, 'x', 1, '[]', '[]', now() FROM incident i CROSS JOIN generate_series(1, 10001) n
                """).update();
        assertThatThrownBy(() -> service.evaluate(null)).isInstanceOf(LiveEvaluationService.LimitExceeded.class);
        verify(labels, never()).find(null);
        verify(incidents, never()).findAll();
        jdbc.sql("DELETE FROM incident_problem").update();
        insertIncidents(100);
        jdbc.sql("""
                INSERT INTO fault_label SELECT 'f' || n, 's', 't', 'x', now(), now()
                FROM generate_series(1, 991) n
                """).update();
        assertThatThrownBy(() -> service.evaluate(null)).isInstanceOf(LiveEvaluationService.LimitExceeded.class);
        verify(labels, never()).find(null);
        verify(incidents, never()).findAll();
    }

    @Test
    void countsAllJsonAndTextBytesBeforeMaterialization() throws Exception {
        insertIncidents(1);
        jdbc.sql("UPDATE incident SET analysis = jsonb_build_object('unused', repeat('x', 4194304))").update();
        mvc.perform(get("/api/evaluation").header("X-Advisor-Token", "ui"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error").isString());
        verify(labels, never()).find(null);
        verify(incidents, never()).findAll();
        jdbc.sql("UPDATE incident SET analysis = NULL, analysis_error = repeat('я', 2097152)").update();
        assertThatThrownBy(() -> service.evaluate(null)).isInstanceOf(LiveEvaluationService.LimitExceeded.class);
        verify(labels, never()).find(null);
        verify(incidents, never()).findAll();
    }

    @Test
    void keepsGlobalAmbiguityAndOriginalMapperErrors() throws Exception {
        jdbc.sql("""
                INSERT INTO fault_label VALUES ('a','one','t','db', '2024-01-01', '2024-01-01 00:01:00'),
                                               ('b','two','t','db', '2024-01-01', '2024-01-01 00:01:00')
                """).update();
        jdbc.sql("""
                INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, tags, started_at, status, analysis_status)
                VALUES (gen_random_uuid(), 1, 'x', 1, '[]', '[]', '2024-01-01 00:00:10', 'OPEN', 'PENDING')
                """).update();
        EvaluationReport report = service.evaluate("one");
        assertThat(report.rows()).hasSize(1);
        assertThat(report.rows().get(0).matchStatus()).isEqualTo(EvaluationReport.MatchStatus.AMBIGUOUS);
        jdbc.sql("UPDATE incident SET hosts = '{}'::jsonb").update();
        assertThatThrownBy(() -> service.evaluate(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsSuspectWorkAndRepeatedCandidateBytesBeforeMaterialization() throws Exception {
        insertIncidents(1);
        jdbc.sql("""
                INSERT INTO fault_label SELECT 'f' || n, 's', 't', 'x', now(), now()
                FROM generate_series(1, 1000) n
                """).update();
        jdbc.sql("""
                UPDATE incident SET analysis = jsonb_build_object('suspects',
                    (SELECT jsonb_agg(jsonb_build_object('host', 'x')) FROM generate_series(1, 1001)))
                """).update();
        mvc.perform(get("/api/evaluation").header("X-Advisor-Token", "ui"))
                .andExpect(status().isPayloadTooLarge());
        verify(labels, never()).find(null);
        verify(incidents, never()).findAll();
        jdbc.sql("UPDATE incident SET analysis = jsonb_build_object('suspects', jsonb_build_array(jsonb_build_object('host', repeat('x', 3000))))").update();
        mvc.perform(get("/api/evaluation").header("X-Advisor-Token", "ui"))
                .andExpect(status().isPayloadTooLarge());
        verify(labels, never()).find(null);
        verify(incidents, never()).findAll();
    }

    @Test
    void rejectsInvalidSuspectTypeAndBoundsScenarioList() throws Exception {
        insertIncidents(1);
        jdbc.sql("UPDATE incident SET analysis = '{\"suspects\":{}}'::jsonb").update();
        mvc.perform(get("/api/evaluation").header("X-Advisor-Token", "ui"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error").value(
                        "Invalid analysis JSON: expected object and suspects array or null"));
        verify(incidents, never()).findAll();
        jdbc.sql("INSERT INTO fault_label VALUES ('f', repeat('я', 513), 't', 'x', now(), now())").update();
        mvc.perform(get("/api/evaluation/scenarios").header("X-Advisor-Token", "ui"))
                .andExpect(status().isPayloadTooLarge());
        jdbc.sql("DELETE FROM fault_label").update();
        jdbc.sql("""
                INSERT INTO fault_label SELECT 'f' || n, 's' || n, 't', 'x', now(), now()
                FROM generate_series(1, 1001) n
                """).update();
        mvc.perform(get("/api/evaluation/scenarios").header("X-Advisor-Token", "ui"))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void rejectsMalformedSuspectElementsBeforeLoadingAndKeepsScalarHosts() throws Exception {
        insertIncidents(1);
        for (String analysis : java.util.List.of(
                "{\"suspects\":[null]}", "{\"suspects\":[1]}", "{\"suspects\":[true]}",
                "{\"suspects\":[\"SECRET_VALUE\"]}", "{\"suspects\":[[]]}",
                "{\"suspects\":[{\"host\":{\"SECRET_VALUE\":1}}]}",
                "{\"suspects\":[{\"host\":[]}]}",
                "{\"suspects\":[{\"host\":\"ok\"},null]}")) {
            jdbc.sql("UPDATE incident SET analysis = CAST(:value AS jsonb)").param("value", analysis).update();
            String response = mvc.perform(get("/api/evaluation").header("X-Advisor-Token", "ui"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error").isString())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("SECRET_VALUE");
            verify(labels, never()).find(null);
            verify(incidents, never()).findAll();
        }
        jdbc.sql("INSERT INTO fault_label VALUES ('valid', 's', 't', 'db', now() - interval '1 minute', now())").update();
        for (String analysis : java.util.List.of(
                "null", "{}", "{\"suspects\":null}", "{\"suspects\":[]}",
                "{\"suspects\":[{}, {\"host\":null}, {\"host\":123}, {\"host\":true},"
                        + "{\"host\":\"db\",\"score\":1.25,\"firstDeviationAt\":\"2024-01-01T00:00:00Z\","
                        + "\"reasons\":[\"reason\"]}]}")) {
            jdbc.sql("UPDATE incident SET analysis = CAST(:value AS jsonb)").param("value", analysis).update();
            assertThat(service.evaluate(null)).isNotNull();
        }
        String emptyHost = "{\"suspects\":[{\"host\":\"\"}]}";
        jdbc.sql("UPDATE incident SET analysis = CAST(:value AS jsonb)").param("value", emptyHost).update();
        assertThat(json.readValue(emptyHost, ru.itmo.advisor.domain.model.AnalysisResult.class)
                .suspects().get(0).host()).isEmpty();
        assertThat(service.evaluate(null)).isNotNull();
    }

    @Test
    void selectedReportMatchesGlobalRowsIncludingBoundariesAndMissingTarget() {
        jdbc.sql("""
                INSERT INTO fault_label VALUES
                  ('target', 'chosen', 't', 'db', '2024-01-01 00:00:00+00', '2024-01-01 00:01:00+00'),
                  ('missing', 'chosen', 't', 'db', '2024-01-01 01:00:00+00', '2024-01-01 01:01:00+00'),
                  ('competing', 'other', 't', 'db', '2024-01-01 00:00:00+00', '2024-01-01 00:00:00+00'),
                  ('unrelated', 'other', 't', 'db', '2024-01-01 02:00:00+00', '2024-01-01 02:01:00+00')
                """).update();
        jdbc.sql("""
                INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, tags, started_at, status, analysis_status)
                VALUES (gen_random_uuid(), 11, 'start', 1, '[]', '[]', '2024-01-01 00:00:00+00', 'OPEN', 'PENDING'),
                       (gen_random_uuid(), 12, 'inside', 1, '[]', '[]', '2024-01-01 00:02:59.999999+00', 'OPEN', 'PENDING'),
                       (gen_random_uuid(), 13, 'excluded-end', 1, '[]', '[]', '2024-01-01 00:03:00+00', 'OPEN', 'PENDING'),
                       (gen_random_uuid(), 14, 'before-start', 1, '[]', '[]', '2023-12-31 23:59:59.999999+00', 'OPEN', 'PENDING')
                """).update();
        EvaluationReport full = service.evaluate(null);
        EvaluationReport selected = service.evaluate("chosen");
        assertThat(selected.rows()).containsExactlyElementsOf(full.rows().stream()
                .filter(row -> "chosen".equals(row.scenarioId())).toList());
        assertThat(selected.rows()).extracting(EvaluationReport.Row::matchStatus)
                .containsExactly(EvaluationReport.MatchStatus.AMBIGUOUS, EvaluationReport.MatchStatus.NO_INCIDENT);
        assertThat(selected.rows().get(0).candidates()).hasSize(2);
    }

    @Test
    void selectedScenarioIgnoresOverLimitAndMalformedUnrelatedHistory() {
        jdbc.sql("""
                INSERT INTO fault_label VALUES ('target', 'chosen', 't', 'db', '2024-01-01', '2024-01-01 00:01:00')
                """).update();
        jdbc.sql("""
                INSERT INTO fault_label SELECT 'other-' || n, 'other', 't', 'db',
                    '2024-01-02'::timestamptz, '2024-01-02'::timestamptz FROM generate_series(1, 1001) n
                """).update();
        insertIncidents(1);
        jdbc.sql("UPDATE incident SET started_at = '2024-01-02', analysis = '{\"suspects\":{}}'::jsonb").update();
        EvaluationReport report = service.evaluate("chosen");
        assertThat(report.rows()).extracting(EvaluationReport.Row::matchStatus)
                .containsExactly(EvaluationReport.MatchStatus.NO_INCIDENT);
        verify(labels, never()).find(null);
        verify(incidents, never()).findAll();
        assertThatThrownBy(() -> service.evaluate(null)).isInstanceOf(LiveEvaluationService.LimitExceeded.class);
    }

    @Test
    void selectedCompetingLabelsOverLimitFailBeforeRepositories() {
        jdbc.sql("""
                INSERT INTO fault_label SELECT 'f' || n, CASE WHEN n = 1 THEN 'chosen' ELSE 'other' END,
                    't', 'db', '2024-01-01'::timestamptz, '2024-01-01'::timestamptz
                FROM generate_series(1, 1001) n
                """).update();
        insertIncidents(1);
        jdbc.sql("UPDATE incident SET started_at = '2024-01-01'").update();
        assertThatThrownBy(() -> service.evaluate("chosen")).isInstanceOf(LiveEvaluationService.LimitExceeded.class);
        verify(labels, never()).findByIds(org.mockito.ArgumentMatchers.any());
        verify(incidents, never()).findByIds(org.mockito.ArgumentMatchers.any());
    }

    private void insertIncidents(int count) {
        jdbc.sql("""
                INSERT INTO incident (id, problem_event_id, trigger_name, severity, hosts, tags, started_at, status, analysis_status)
                SELECT gen_random_uuid(), (SELECT COALESCE(max(problem_event_id), 0) FROM incident) + n,
                       'x', 1, '[]', '[]', now(), 'OPEN', 'PENDING' FROM generate_series(1, :count) n
                """).param("count", count).update();
    }
}
