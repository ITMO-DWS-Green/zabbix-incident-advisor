package ru.itmo.advisor.analysis.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.START;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.WINDOW;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.context;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.incident;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.domain.fixtures.CascadeFixture;
import ru.itmo.advisor.domain.model.Explanation;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.LogEvidence;
import ru.itmo.advisor.domain.model.MetricDeviation;
import ru.itmo.advisor.domain.model.Suspect;
import ru.itmo.advisor.domain.port.LlmClient;

class TemplateExplanationWriterTest {

    private static final String TRIGGER = "gateway: доля 5xx выше 10%";
    private static final String REASON = "У orders ошибки при вызове database";
    private static final String LOG_LINE = "Unhandled exception: Deadlock detected";

    private final IncidentContext cascade = context(incident(1, "gateway", TRIGGER, START), WINDOW,
            CascadeFixture.GRAPH, List.of("gateway", "orders", "database"), List.of(), List.of());
    private final List<Suspect> suspects = List.of(new Suspect("database", 1, CascadeFixture.FAULT_START,
            List.of(REASON)));
    private final List<MetricDeviation> deviations = List.of(new MetricDeviation("database", "database-1",
            "Средняя задержка", "ms", 50, 4000, CascadeFixture.FAULT_START, 20));
    private final List<LogEvidence> logs = List.of(new LogEvidence("database", "database-log", "Deadlock detected",
            LOG_LINE, 3, CascadeFixture.FAULT_START, true));

    @Test
    void templateExplainsCascadeWithMetricAndLogEvidence() {
        Explanation explanation = template().write(cascade, suspects, deviations, logs);

        assertThat(explanation.generatedBy()).isEqualTo("template");
        assertThat(explanation.summary()).contains("Вероятный источник — database", REASON);
        assertThat(explanation.details()).contains(TRIGGER, "gateway, orders, database", REASON,
                "database / Средняя задержка", "обычно 50.0, стало 4000.0", CascadeFixture.FAULT_START.toString(),
                "database: " + LOG_LINE + " (3 раз)");
    }

    @Test
    void templateReportsMissingEvidenceWithoutNamingASource() {
        Explanation explanation = template().write(cascade, List.of(), List.of(), List.of());

        assertThat(explanation.generatedBy()).isEqualTo("template");
        assertThat(explanation.summary()).contains("Источник не определён", "отклонений метрик не найдено");
        assertThat(explanation.details()).contains(TRIGGER).doesNotContain("Логи:");
    }

    @Test
    @Tag("mvp-target")
    void fallsBackToSameTemplateWhenLlmNamesServiceOutsideFacts() {
        Explanation expected = template().write(cascade, suspects, deviations, logs);
        List<LlmClient.Request> requests = new ArrayList<>();
        LlmClient llm = new LlmClient() {
            @Override
            public Response complete(Request request) {
                requests.add(request);
                return new Response("Вероятный источник — unknown-worker.", "fake-llm", 1);
            }

            @Override
            public boolean enabled() {
                return true;
            }
        };

        Explanation actual = new TemplateExplanationWriter(llm).write(cascade, suspects, deviations, logs);

        assertThat(requests).hasSize(1);
        assertThat(actual).isEqualTo(expected);
    }

    private static TemplateExplanationWriter template() {
        return new TemplateExplanationWriter(new LlmClient() {
            @Override
            public Response complete(Request request) {
                throw new AssertionError("При отключённой LLM используется шаблон");
            }

            @Override
            public boolean enabled() {
                return false;
            }
        });
    }
}
