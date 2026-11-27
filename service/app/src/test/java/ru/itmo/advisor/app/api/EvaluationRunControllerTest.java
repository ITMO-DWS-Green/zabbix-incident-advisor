package ru.itmo.advisor.app.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.itmo.advisor.app.evaluation.EvaluationRun;
import ru.itmo.advisor.app.evaluation.EvaluationRunRepository;
import ru.itmo.advisor.app.evaluation.EvaluationRunService;
import ru.itmo.advisor.domain.model.FaultLabel;

class EvaluationRunControllerTest {
    private final EvaluationRunService service = mock(EvaluationRunService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new EvaluationRunController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();
    }

    @Test
    void rejectsMissingNaiveInvalidAndExtraFields() throws Exception {
        for (String body : new String[] {"{}", "null", "{", "{\"from\":\"2026-01-01T00:00:00\",\"to\":\"2026-01-02T00:00:00Z\"}",
                "{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-02T00:00:00Z\",\"scenarioId\":\"x\"}"}) {
            mvc.perform(post("/api/evaluation/runs").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isNotEmpty());
        }
        verify(service, never()).create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingIdAndUnsupportedMutation() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.find(id)).thenReturn(Optional.empty());
        mvc.perform(get("/api/evaluation/runs/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/evaluation/runs/not-a-uuid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        mvc.perform(put("/api/evaluation/runs/" + id)).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/evaluation/runs?limit=101")).andExpect(status().isBadRequest());
    }

    @Test
    void internalIllegalArgumentIsNotMappedTo400OrExposed() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-02T00:00:00Z");
        when(service.create(from, to)).thenThrow(new IllegalArgumentException("SECRET_INTERNAL_DATA"));
        assertThatThrownBy(() -> mvc.perform(post("/api/evaluation/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-02T00:00:00Z\"}")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizedSerializedSnapshotIsReportedAs400WithoutUpdating() throws Exception {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-02T00:00:00Z");
        String chunk = "x".repeat(1024);
        FaultLabel label = new FaultLabel("s", "f", "t", chunk, from, to);
        EvaluationRun run = new EvaluationRun(UUID.randomUUID(), from, from, to, 1, "v", "scope",
                Collections.nCopies(EvaluationRunService.MAX_SNAPSHOT_JSON_BYTES / chunk.length() + 1, label),
                List.of(), null);
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class, org.mockito.Mockito.RETURNS_SELF);
        when(jdbc.sql(any(String.class))).thenReturn(statement);
        EvaluationRunRepository repository = new EvaluationRunRepository(jdbc,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        when(service.create(from, to)).thenAnswer(invocation -> {
            repository.insert(run);
            return run;
        });

        mvc.perform(post("/api/evaluation/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-02T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Слишком большой JSON снимка: максимум "
                        + EvaluationRunService.MAX_SNAPSHOT_JSON_BYTES + " байт"));
        verify(statement, never()).update();
    }

    @Test
    void serviceValidationIsReportedAs400() throws Exception {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        when(service.create(from, from)).thenThrow(new EvaluationRunService.Validation("некорректное окно"));
        mvc.perform(post("/api/evaluation/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isNotEmpty());
    }
}
