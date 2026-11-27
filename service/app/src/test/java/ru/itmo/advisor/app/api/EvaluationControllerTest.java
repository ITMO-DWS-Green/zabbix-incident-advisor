package ru.itmo.advisor.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.START;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.itmo.advisor.app.evaluation.LiveEvaluationService;
import ru.itmo.advisor.app.storage.LabelRepository;
import ru.itmo.advisor.domain.model.AnalysisResult;
import ru.itmo.advisor.domain.model.EvaluationReport;
import ru.itmo.advisor.domain.model.FaultLabel;

class EvaluationControllerTest {

    private final LabelRepository labels = mock(LabelRepository.class);
    private final LiveEvaluationService liveEvaluation = mock(LiveEvaluationService.class);
    private final EvaluationController controller = new EvaluationController(labels, liveEvaluation);

    @Test
    void delegatesEvaluationAndScenariosWithoutChangingResults() {
        EvaluationReport full = mock(EvaluationReport.class);
        EvaluationReport filtered = mock(EvaluationReport.class);
        List<String> scenarios = List.of("one", "two");
        when(liveEvaluation.evaluate(null)).thenReturn(full);
        when(liveEvaluation.evaluate("one")).thenReturn(filtered);
        when(liveEvaluation.scenarios()).thenReturn(scenarios);

        assertThat(controller.evaluation(null)).isSameAs(full);
        assertThat(controller.evaluation("one")).isSameAs(filtered);
        assertThat(controller.scenarios()).isSameAs(scenarios);
        verify(liveEvaluation).evaluate(null);
        verify(liveEvaluation).evaluate("one");
        verify(liveEvaluation).scenarios();
        org.mockito.Mockito.verifyNoInteractions(labels);
    }

    @Test
    void mapsServiceFailuresToHttpResponsesWithoutHidingUnexpectedErrors() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(liveEvaluation.evaluate("large")).thenThrow(new LiveEvaluationService.LimitExceeded());
        when(liveEvaluation.evaluate("invalid")).thenThrow(new LiveEvaluationService.InvalidAnalysis());
        when(liveEvaluation.evaluate("unexpected")).thenThrow(new IllegalStateException("unexpected"));
        when(liveEvaluation.scenarios()).thenThrow(new LiveEvaluationService.LimitExceeded());

        mvc.perform(get("/api/evaluation").param("scenarioId", "large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("Evaluation input exceeds emergency limits"));
        mvc.perform(get("/api/evaluation").param("scenarioId", "invalid"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Invalid analysis JSON: expected object and suspects array or null"));
        mvc.perform(get("/api/evaluation/scenarios"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("Evaluation input exceeds emergency limits"));
        assertThatThrownBy(() -> mvc.perform(get("/api/evaluation").param("scenarioId", "unexpected")))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsLabelsWithMissingOrReversedTimestampsWithoutSaving() {
        List<FaultLabel> invalidLabels = List.of(
                new FaultLabel("scenario", "reversed", "type", "database", START, START.minusSeconds(1)),
                new FaultLabel("scenario", "missing-start", "type", "database", null, START),
                new FaultLabel("scenario", "missing-end", "type", "database", START, null),
                new FaultLabel("scenario", "missing-both", "type", "database", null, null),
                new FaultLabel("scenario", "submicro-reversed", "type", "database", START.plusNanos(999), START.plusNanos(1)));

        assertThat(controller.label(null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for (FaultLabel label : invalidLabels) {
            assertThat(controller.label(label).getStatusCode()).as(label.faultId()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        verify(labels, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void acceptsEqualTimestampsAndSavesLabel() {
        FaultLabel label = new FaultLabel("scenario", "equal", "type", "database", START, START);
        when(labels.save(label)).thenReturn(true);

        assertThat(controller.label(label).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(labels).save(label);
    }

    @Test
    void roundsBothBoundariesToNearestMicrosecondWithTiesTowardLaterInstant() {
        for (String[] times : List.of(
                new String[] {"2026-10-29T12:00:00.123456499Z", "2026-10-29T12:00:00.123456500Z",
                        "2026-10-29T12:00:00.123456Z", "2026-10-29T12:00:00.123457Z"},
                new String[] {"1969-12-31T23:59:59.999998499Z", "1969-12-31T23:59:59.999998500Z",
                        "1969-12-31T23:59:59.999998Z", "1969-12-31T23:59:59.999999Z"},
                new String[] {"1969-12-31T23:59:59.999999500Z", "1970-01-01T00:00:00.000000999Z",
                        "1970-01-01T00:00:00Z", "1970-01-01T00:00:00.000001Z"},
                new String[] {"2026-10-29T12:00:00.999999500Z", "2026-10-29T12:00:01.000001999Z",
                        "2026-10-29T12:00:01Z", "2026-10-29T12:00:01.000002Z"})) {
            FaultLabel input = new FaultLabel("scenario", "fine", "type", "source",
                    Instant.parse(times[0]), Instant.parse(times[1]));
            FaultLabel canonical = new FaultLabel("scenario", "fine", "type", "source",
                    Instant.parse(times[2]), Instant.parse(times[3]));
            when(labels.save(canonical)).thenReturn(true);
            assertThat(controller.label(input).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            verify(labels).save(canonical);
            assertThat(canonical.end()).isAfterOrEqualTo(canonical.start());
        }
    }

    @Test
    void validatesFiniteTimestampRangeBeforeRoundingOrSaving() {
        Instant min = Instant.parse("0001-01-01T00:00:00Z");
        Instant max = Instant.parse("9999-12-31T23:59:59.999999Z");
        for (Instant invalid : List.of(Instant.MIN, min.minusNanos(1), max.plusNanos(1), Instant.MAX)) {
            FaultLabel label = new FaultLabel("scenario", "out-of-range", "type", "source", invalid, invalid);
            assertThat(controller.label(label).getStatusCode()).as(invalid.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(controller.label(new FaultLabel("scenario", "end-out-of-range", "type", "source", min, max.plusNanos(1)))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(labels, never()).save(org.mockito.ArgumentMatchers.any());

        FaultLabel boundary = new FaultLabel("scenario", "boundary", "type", "source", min, max);
        when(labels.save(boundary)).thenReturn(true);
        assertThat(controller.label(boundary).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(labels).save(boundary);

        FaultLabel justInside = new FaultLabel("scenario", "inside", "type", "source",
                min.plusNanos(500), max.minusNanos(500));
        FaultLabel roundedInside = new FaultLabel("scenario", "inside", "type", "source", min.plusNanos(1000), max);
        when(labels.save(roundedInside)).thenReturn(true);
        assertThat(controller.label(justInside).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(labels).save(roundedInside);
    }

    @Test
    void rejectsMissingAndBlankRequiredFields() {
        List<FaultLabel> invalid = List.of(
                new FaultLabel(null, "f", "type", "source", START, START),
                new FaultLabel(" ", "f", "type", "source", START, START),
                new FaultLabel("scenario", null, "type", "source", START, START),
                new FaultLabel("scenario", "", "type", "source", START, START),
                new FaultLabel("scenario", "f", null, "source", START, START),
                new FaultLabel("scenario", "f", " ", "source", START, START),
                new FaultLabel("scenario", "f", "type", null, START, START),
                new FaultLabel("scenario", "f", "type", " ", START, START));
        for (FaultLabel label : invalid) {
            assertThat(controller.label(label).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        verify(labels, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsNulInEveryRequiredTextFieldWithoutSaving() {
        List<FaultLabel> invalid = List.of(
                new FaultLabel("sce\0nario", "f", "type", "source", START, START),
                new FaultLabel("scenario", "f\0ault", "type", "source", START, START),
                new FaultLabel("scenario", "f", "ty\0pe", "source", START, START),
                new FaultLabel("scenario", "f", "type", "sou\0rce", START, START));
        for (FaultLabel label : invalid) {
            assertThat(controller.label(label).getStatusCode()).as(label.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        verify(labels, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reportsConflictingFaultId() {
        FaultLabel label = new FaultLabel("scenario", "f", "type", "source", START, START);
        when(labels.save(label)).thenReturn(false);
        var response = controller.label(label);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().toString()).contains("faultId", "f");
        verify(labels).save(label);
    }

    @Test
    void suspectJacksonContractAllowsNullableAndScalarFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AnalysisResult result = mapper.readValue("""
                {"suspects":[{}, {"host":null,"score":null,"firstDeviationAt":null,"reasons":null},
                             {"host":123}, {"host":true}, {"host":""},
                             {"host":"db","score":1.25,"firstDeviationAt":"2024-01-01T00:00:00Z","reasons":["reason"]}]}
                """, AnalysisResult.class);
        assertThat(result.suspects()).extracting(ru.itmo.advisor.domain.model.Suspect::host)
                .containsExactly(null, null, "123", "true", "", "db");
        assertThat(result.suspects().get(1).score()).isZero();
        assertThat(result.suspects().get(1).reasons()).isEmpty();
        for (String invalid : List.of("null", "1", "true", "[]", "{\"host\":{}}", "{\"host\":[]}")) {
            assertThatThrownBy(() -> mapper.readValue("{\"suspects\":[" + invalid + "]}", AnalysisResult.class))
                    .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        }
    }

    @Test
    void analysisJsonPreservesLegacyAndCompletedTimestamps() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String legacyJson = """
                {"analyzedAt":"2024-01-01T00:00:00Z","scope":[],"suspects":[],
                 "deviations":[],"logEvidence":[],"explanation":null}
                """;
        AnalysisResult legacy = mapper.readValue(legacyJson, AnalysisResult.class);
        assertThat(legacy.analyzedAt()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
        assertThat(legacy.completedAt()).isNull();
        assertThat(new AnalysisResult(START, null, null, null, null, null).completedAt()).isNull();
        AnalysisResult completed = new AnalysisResult(START, List.of(), List.of(), List.of(), List.of(), null,
                START.plusSeconds(10));
        assertThat(mapper.readValue(legacyJson.replace("\"explanation\":null",
                "\"explanation\":null,\"completedAt\":\"2024-01-01T00:00:10Z\""), AnalysisResult.class)
                .completedAt()).isEqualTo(Instant.parse("2024-01-01T00:00:10Z"));
        assertThat(mapper.readValue(mapper.writeValueAsString(completed), AnalysisResult.class)).isEqualTo(completed);
        assertThat(mapper.readValue(mapper.writeValueAsString(legacy), AnalysisResult.class)).isEqualTo(legacy);
    }

}
