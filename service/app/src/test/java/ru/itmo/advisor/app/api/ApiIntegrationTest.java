package ru.itmo.advisor.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Сквозь HTTP и настоящую PostgreSQL: коннектор → инцидент → API интерфейса. Zabbix не нужен. */
@SpringBootTest(properties = {
    "advisor.analysis.delay=1h",          // разбор в тесте не запускаем — для него нужен Zabbix
    "advisor.zabbix.token=test",
    "advisor.security.ui-token=ui",
    "advisor.security.connector-token=conn",
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
