package ru.itmo.advisor.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.itmo.advisor.app.storage.LabelRepository;
import ru.itmo.advisor.app.storage.RetentionCleanup;
import ru.itmo.advisor.domain.model.FaultLabel;

/** Сквозь HTTP и настоящую PostgreSQL: коннектор → инцидент → API интерфейса. Zabbix не нужен. */
@SpringBootTest(properties = {
    "advisor.analysis.delay=1h",          // разбор в тесте не запускаем — для него нужен Zabbix
    "advisor.zabbix.token=test",
    "advisor.security.ui-token=ui",
    "advisor.security.connector-token=conn",
    "ADVISOR_RETENTION_ADMIN_TOKEN=admin-settings",
    "ADVISOR_RETENTION_INCIDENTS=60d",
    "ADVISOR_RETENTION_LLM_AUDIT=14d",
})
@AutoConfigureMockMvc
@Testcontainers
class ApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RetentionCleanup retentionCleanup;

    @Autowired
    LabelRepository labels;

    @Test
    @Transactional
    void retentionDeletesOnlyExpiredRecords() throws Exception {
        mvc.perform(post("/api/settings/retention").header("X-Advisor-Token", "ui")
                        .header("X-Advisor-Retention-Token", "admin-settings").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"incidents":"30d","llmAudit":"7d"}
                                """))
                .andExpect(status().isOk());

        jdbc.sql("""
                INSERT INTO incident (id, problem_event_id, trigger_name, severity, started_at, resolved_at,
                                      status, analysis_status)
                VALUES (gen_random_uuid(), 3601, 'old resolved', 2, now() - interval '60 days',
                        now() - interval '31 days', 'RESOLVED', 'DONE'),
                       (gen_random_uuid(), 3602, 'recently resolved', 2, now() - interval '60 days',
                        now() - interval '29 days', 'RESOLVED', 'DONE'),
                       (gen_random_uuid(), 3603, 'still open', 2, now() - interval '60 days',
                        NULL, 'OPEN', 'DONE')
                """).update();
        jdbc.sql("""
                INSERT INTO incident_problem (event_id, incident_id, trigger_name, severity, started_at, resolved_at)
                SELECT problem_event_id, id, trigger_name, severity, started_at, resolved_at
                FROM incident WHERE problem_event_id BETWEEN 3601 AND 3603
                """).update();
        jdbc.sql("""
                INSERT INTO feedback (incident_id, verdict)
                SELECT id, 'CORRECT' FROM incident WHERE problem_event_id BETWEEN 3601 AND 3602
                """).update();
        jdbc.sql("""
                INSERT INTO llm_audit (created_at, purpose, request, duration_ms)
                VALUES (now() - interval '8 days', 'retention-expired', 'request', 1),
                       (now() - interval '6 days', 'retention-recent', 'request', 1)
                """).update();

        retentionCleanup.deleteExpired();

        assertThat(jdbc.sql("SELECT problem_event_id FROM incident WHERE problem_event_id BETWEEN 3601 AND 3603")
                .query(Long.class).list()).containsExactlyInAnyOrder(3602L, 3603L);
        assertThat(jdbc.sql("SELECT event_id FROM incident_problem WHERE event_id BETWEEN 3601 AND 3603")
                .query(Long.class).list()).containsExactlyInAnyOrder(3602L, 3603L);
        assertThat(jdbc.sql("SELECT incident_id FROM feedback").query(UUID.class).list())
                .containsExactly(jdbc.sql("SELECT id FROM incident WHERE problem_event_id = 3602")
                        .query(UUID.class).single());
        assertThat(jdbc.sql("SELECT purpose FROM llm_audit WHERE purpose LIKE 'retention-%'")
                .query(String.class).list()).containsExactly("retention-recent");
    }

    @Test
    @Transactional
    void onlyZabbixAdminsCanChangeRetentionSettings() throws Exception {
        String path = "/api/settings/retention";
        String valid = "{\"incidents\":\"30d\",\"llmAudit\":\"7d\"}";
        mvc.perform(post(path).header("X-Advisor-Retention-Token", "admin-settings")
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("X-Advisor-Token", "ui")
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).header("X-Advisor-Token", "ui"))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).header("X-Advisor-Token", "ui").header("X-Advisor-Retention-Token", "admin-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidents").value("60d"))
                .andExpect(jsonPath("$.llmAudit").value("14d"));

        mvc.perform(post(path).header("X-Advisor-Token", "ui").header("X-Advisor-Retention-Token", "admin-settings")
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isOk());
        mvc.perform(post(path).header("X-Advisor-Token", "ui").header("X-Advisor-Retention-Token", "admin-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidents\":\"0d\",\"llmAudit\":\"2d\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path).header("X-Advisor-Token", "ui").header("X-Advisor-Retention-Token", "admin-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidents").value("30d"))
                .andExpect(jsonPath("$.llmAudit").value("7d"));
    }

    @Test
    void problemFromConnectorBecomesIncidentAndRecoveryResolvesIt() throws Exception {
        String problem = """
                {"clock":1761739200,"ns":0,"value":1,"eventid":1001,"name":"database: задержка выше 1 с","severity":2,"hosts":[{"host":"database","name":"database"}],"groups":["test-server"],"tags":[{"tag":"service","value":"database"}]}
                """;
        mvc.perform(post("/zabbix/connector/events").header("Authorization", "Bearer conn")
                        .contentType("application/x-ndjson").content(problem))
                .andExpect(status().isOk());

        mvc.perform(get("/api/incidents").header("X-Advisor-Token", "ui"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].problemEventId").value(1001))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].analysisStatus").value("PENDING"))
                .andExpect(jsonPath("$[0].hosts[0].host").value("database"));

        String recovery = "{\"clock\":1761739500,\"ns\":0,\"value\":0,\"eventid\":1002,\"p_eventid\":1001}\n";
        mvc.perform(post("/zabbix/connector/events").header("Authorization", "Bearer conn")
                        .contentType("application/x-ndjson").content(recovery))
                .andExpect(status().isOk());

        mvc.perform(get("/api/incidents").header("X-Advisor-Token", "ui"))
                .andExpect(jsonPath("$[0].status").value("RESOLVED"));
    }

    /** Zabbix в тесте нет, карта связей пустая — объединяются проблемы одного хоста. */
    @Test
    void relatedProblemsAreOneIncidentResolvedWithTheLastProblem() throws Exception {
        events("""
                {"clock":1761700000,"ns":0,"value":1,"eventid":2001,"name":"payments: задержка выше 1 с","severity":2,"hosts":[{"host":"payments","name":"payments"}],"tags":[]}
                {"clock":1761700020,"ns":0,"value":1,"eventid":2002,"name":"payments: доля 5xx выше 10%","severity":3,"hosts":[{"host":"payments","name":"payments"}],"tags":[]}
                """);
        events("""
                {"clock":1761700020,"ns":0,"value":1,"eventid":2002,"name":"payments: доля 5xx выше 10%","severity":3,"hosts":[{"host":"payments","name":"payments"}],"tags":[]}
                """);

        JsonNode incident = incidentWithEvent(2001);
        assertThat(incident.get("problemCount").asInt()).isEqualTo(2);
        assertThat(incident.get("severity").asInt()).isEqualTo(3);
        String id = incident.get("id").asText();
        mvc.perform(get("/api/incidents/" + id).header("X-Advisor-Token", "ui"))
                .andExpect(jsonPath("$.problems.length()").value(2))
                .andExpect(jsonPath("$.problems[1].eventId").value(2002))
                .andExpect(jsonPath("$.problems[1].triggerName").value("payments: доля 5xx выше 10%"));

        events("{\"clock\":1761700100,\"ns\":0,\"value\":0,\"eventid\":2003,\"p_eventid\":2001}\n");
        assertThat(incidentWithEvent(2001).get("status").asText()).isEqualTo("OPEN");
        events("{\"clock\":1761700200,\"ns\":0,\"value\":0,\"eventid\":2004,\"p_eventid\":2002}\n");
        assertThat(incidentWithEvent(2001).get("status").asText()).isEqualTo("RESOLVED");
        assertThat(incidentWithEvent(2001).get("resolvedAt").asText()).startsWith("2025-10-29T01:10:00");
    }

    private void events(String ndjson) throws Exception {
        mvc.perform(post("/zabbix/connector/events").header("Authorization", "Bearer conn")
                        .contentType("application/x-ndjson").content(ndjson))
                .andExpect(status().isOk());
    }

    private JsonNode incidentWithEvent(long eventId) throws Exception {
        String body = mvc.perform(get("/api/incidents").header("X-Advisor-Token", "ui"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode i : json.readTree(body)) {
            if (i.get("problemEventId").asLong() == eventId) {
                return i;
            }
        }
        throw new AssertionError("нет инцидента с проблемой " + eventId + ": " + body);
    }

    @Test
    void tokensAreRequired() throws Exception {
        mvc.perform(get("/api/incidents")).andExpect(status().isUnauthorized());
        mvc.perform(post("/zabbix/connector/events").content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void labelRetriesOnlyUpdateTheEndOfTheSameFault() throws Exception {
        String id = "label-retry-" + UUID.randomUUID();
        Instant start = Instant.parse("2026-10-29T12:00:00.123456789Z");
        Instant canonicalStart = Instant.parse("2026-10-29T12:00:00.123457Z");
        FaultLabel original = new FaultLabel("scenario-retry", id, "LATENCY", "database", start, start.plusSeconds(60));
        postLabel(original, 202);
        postLabel(original, 202);
        FaultLabel updated = new FaultLabel(original.scenarioId(), id, original.type(), original.source(),
                start, start.plusSeconds(120).plusNanos(99));
        postLabel(updated, 202);
        FaultLabel stored = new FaultLabel(original.scenarioId(), id, original.type(), original.source(),
                canonicalStart, canonicalStart.plusSeconds(120));
        assertThat(labels.find("scenario-retry")).containsExactly(stored);
        postLabel(new FaultLabel(original.scenarioId(), id, original.type(), original.source(),
                canonicalStart.plusNanos(1), canonicalStart.plusSeconds(120)), 202);
        assertThat(labels.find("scenario-retry")).containsExactly(stored);

        for (FaultLabel conflict : java.util.List.of(
                new FaultLabel("different", id, original.type(), original.source(), start, start.plusSeconds(180)),
                new FaultLabel(original.scenarioId(), id, "different", original.source(), start, start.plusSeconds(180)),
                new FaultLabel(original.scenarioId(), id, original.type(), "different", start, start.plusSeconds(180)),
                new FaultLabel(original.scenarioId(), id, original.type(), original.source(),
                        start.plusNanos(1000), start.plusSeconds(180)))) {
            postLabel(conflict, 409);
            assertThat(labels.find("scenario-retry")).containsExactly(stored);
        }
        FaultLabel shortened = new FaultLabel(original.scenarioId(), id, original.type(), original.source(), start, start);
        postLabel(shortened, 202);
        assertThat(labels.find("scenario-retry")).containsExactly(new FaultLabel(
                original.scenarioId(), id, original.type(), original.source(), canonicalStart, canonicalStart));
    }

    @Test
    @Transactional
    void nanosecondBoundsAreRoundedAcrossEpochAndSecond() throws Exception {
        String scenario = "label-precision-" + UUID.randomUUID();
        FaultLabel beforeEpoch = new FaultLabel(scenario, UUID.randomUUID().toString(), "type", "source",
                Instant.parse("1969-12-31T23:59:59.999999999Z"), Instant.parse("1970-01-01T00:00:00.000000999Z"));
        FaultLabel secondBoundary = new FaultLabel(scenario, UUID.randomUUID().toString(), "type", "source",
                Instant.parse("2026-10-29T12:00:00.999999999Z"), Instant.parse("2026-10-29T12:00:01.000001999Z"));
        postLabel(beforeEpoch, 202);
        postLabel(secondBoundary, 202);
        postLabel(beforeEpoch, 202);
        postLabel(secondBoundary, 202);
        assertThat(labels.find(scenario)).containsExactly(
                new FaultLabel(scenario, beforeEpoch.faultId(), "type", "source",
                        Instant.EPOCH, Instant.parse("1970-01-01T00:00:00.000001Z")),
                new FaultLabel(scenario, secondBoundary.faultId(), "type", "source",
                        Instant.parse("2026-10-29T12:00:01Z"), Instant.parse("2026-10-29T12:00:01.000002Z")));
    }

    @Test
    @Transactional
    void retriesLabelsWrittenByLegacyTimestampBinding() throws Exception {
        String scenario = "legacy-label-" + UUID.randomUUID();
        for (String[] times : java.util.List.of(
                new String[] {"2026-10-29T12:00:00.123456499Z", "2026-10-29T12:00:00.123456Z"},
                new String[] {"2026-10-29T12:00:00.123456500Z", "2026-10-29T12:00:00.123457Z"},
                new String[] {"1969-12-31T23:59:59.999998499Z", "1969-12-31T23:59:59.999998Z"},
                new String[] {"1969-12-31T23:59:59.999998500Z", "1969-12-31T23:59:59.999999Z"},
                new String[] {"1969-12-31T23:59:59.999999500Z", "1970-01-01T00:00:00Z"},
                new String[] {"2026-10-29T12:00:00.999999499Z", "2026-10-29T12:00:00.999999Z"},
                new String[] {"2026-10-29T12:00:00.999999500Z", "2026-10-29T12:00:01Z"})) {
            Instant originalStart = Instant.parse(times[0]);
            Instant expectedStart = Instant.parse(times[1]);
            FaultLabel legacy = new FaultLabel(scenario, UUID.randomUUID().toString(), "type", "source",
                    originalStart, originalStart.plusSeconds(1));
            // Reproduce the old repository: bind Timestamp.from without pre-normalization.
            jdbc.sql("""
                    INSERT INTO fault_label (fault_id, scenario_id, type, source, started_at, ended_at)
                    VALUES (:id, :scenario, :type, :source, :start, :end)
                    """).param("id", legacy.faultId()).param("scenario", scenario)
                    .param("type", legacy.type()).param("source", legacy.source())
                    .param("start", Timestamp.from(legacy.start())).param("end", Timestamp.from(legacy.end())).update();
            assertThat(labels.find(scenario)).contains(new FaultLabel(scenario, legacy.faultId(), "type", "source",
                    expectedStart, expectedStart.plusSeconds(1)));
            FaultLabel retry = new FaultLabel(scenario, legacy.faultId(), "type", "source",
                    originalStart, originalStart.plusSeconds(2));
            postLabel(retry, 202);
            assertThat(labels.find(scenario)).contains(new FaultLabel(scenario, legacy.faultId(), "type", "source",
                    expectedStart, expectedStart.plusSeconds(2)));
        }
    }

    @Test
    @Transactional
    void malformedLabelsAreRejectedWithoutWriting() throws Exception {
        String valid = """
                {"scenarioId":"malformed-scenario","faultId":"malformed-fault","type":"LATENCY","source":"database",
                 "start":"2026-10-29T12:00:00Z","end":"2026-10-29T12:05:00Z"}
                """;
        for (String payload : java.util.List.of(
                "{", "null", valid.replace("\"scenarioId\":\"malformed-scenario\",", ""),
                valid.replace("\"faultId\":\"malformed-fault\"", "\"faultId\":null"),
                valid.replace("\"type\":\"LATENCY\"", "\"type\":\" \""),
                valid.replace("\"source\":\"database\"", "\"source\":null"),
                valid.replace("2026-10-29T12:00:00Z", "not-a-date"),
                valid.replace("2026-10-29T12:00:00Z", "0000-12-31T23:59:59.999999999Z"),
                valid.replace("2026-10-29T12:05:00Z", "+10000-01-01T00:00:00Z"),
                valid.replace("2026-10-29T12:05:00Z", "9999-12-31T23:59:59.999999500Z"),
                valid.replace("2026-10-29T12:05:00Z", "2026-10-29T11:59:59Z"),
                valid.replace("2026-10-29T12:00:00Z", "2026-10-29T12:00:00.000000999Z")
                        .replace("2026-10-29T12:05:00Z", "2026-10-29T12:00:00.000000001Z"))) {
            mvc.perform(post("/api/v1/labels").contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isBadRequest());
        }
        assertThat(labels.find("malformed-scenario")).isEmpty();
    }

    private void postLabel(FaultLabel label, int statusCode) throws Exception {
        var result = mvc.perform(post("/api/v1/labels").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(label)))
                .andExpect(status().is(statusCode));
        if (statusCode == 409) {
            result.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("faultId")));
        }
    }

    @Test
    void labelsFromTestServerAreEvaluated() throws Exception {
        mvc.perform(post("/api/v1/labels").contentType(MediaType.APPLICATION_JSON).content("""
                        {"scenarioId":"s1","faultId":"database-1","type":"LATENCY_SPIKE","source":"database",
                         "start":"2026-10-29T12:00:00Z","end":"2026-10-29T12:05:00Z"}"""))
                .andExpect(status().isAccepted());

        mvc.perform(get("/api/evaluation").param("scenarioId", "s1").header("X-Advisor-Token", "ui"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.faults").value(1));
    }
}
