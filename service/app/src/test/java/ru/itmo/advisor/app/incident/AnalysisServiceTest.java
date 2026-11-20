package ru.itmo.advisor.app.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.START;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.context;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.analysis.explain.TemplateExplanationWriter;
import ru.itmo.advisor.analysis.graph.TagServiceGraphProvider;
import ru.itmo.advisor.analysis.logs.PatternLogEvidenceExtractor;
import ru.itmo.advisor.analysis.metrics.BaselineDeviationDetector;
import ru.itmo.advisor.analysis.ranking.EarliestDeviationRanker;
import ru.itmo.advisor.analysis.ranking.NoCauseSymptomPolicy;
import ru.itmo.advisor.app.config.AdvisorProperties;
import ru.itmo.advisor.domain.fixtures.CascadeFixture;
import ru.itmo.advisor.domain.fixtures.IncidentFixtures;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.MetricDeviation;
import ru.itmo.advisor.domain.model.MetricSeries;
import ru.itmo.advisor.domain.model.ServiceGraph;
import ru.itmo.advisor.domain.model.TimeWindow;
import ru.itmo.advisor.domain.port.ServiceGraphProvider;
import ru.itmo.advisor.domain.port.ZabbixWriter;
import ru.itmo.advisor.llm.StubLlmClient;

/**
 * Конвейер на подделках: каскад «database тормозит → orders и gateway ошибаются».
 * Пример, как тестировать свою бизнес-логику без Zabbix и БД.
 */
class AnalysisServiceTest {

    private final InMemoryIncidentRepository incidents = new InMemoryIncidentRepository();
    private final List<String> comments = new ArrayList<>();

    @Test
    void cascadeFromDatabaseIsTracedToDatabaseAndCommentedInZabbix() {
        Incident incident = incident(42, "gateway", "gateway: доля 5xx выше 10%", START);
        incidents.createIfAbsent(incident);

        service(new FakeSource()).analyze(incident.id());

        Incident analyzed = incidents.find(incident.id()).orElseThrow();
        assertThat(analyzed.analysisStatus()).isEqualTo(AnalysisStatus.DONE);
        assertThat(analyzed.analysis().topSuspect()).isEqualTo("database");
        assertThat(analyzed.analysis().scope()).contains("gateway", "orders", "database");
        assertThat(analyzed.analysis().deviations()).extracting(MetricDeviation::host)
                .containsExactly("database", "orders", "gateway");
        assertThat(analyzed.analysis().deviations().get(0).startedAt()).isEqualTo(CascadeFixture.FAULT_START);
        assertThat(comments).singleElement().asString()
                .startsWith(AnalysisService.COMMENT_PREFIX).contains("database");
    }

    @Test
    void analysisCarriesAtLeastTwoCharacteristicLogLinesOfTheSourceAndNoInfoNoise() {
        Incident incident = incident(44, "gateway", "gateway: доля 5xx выше 10%", START);
        incidents.createIfAbsent(incident);

        service(new FakeSource()).analyze(incident.id());

        var evidence = incidents.find(incident.id()).orElseThrow().analysis().logEvidence();
        assertThat(evidence).filteredOn(e -> e.host().equals("database")).hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(e -> assertThat(e.example()).contains(" ERROR ").contains("Unhandled exception"));
        assertThat(evidence).noneMatch(e -> e.example().contains(" INFO "));
        assertThat(incidents.find(incident.id()).orElseThrow().analysis().explanation().details())
                .contains("Логи:").contains("Deadlock detected");
    }

    @Test
    void failureIsStoredAndDoesNotThrow() {
        Incident incident = incident(43, "gateway", "x", START);
        incidents.createIfAbsent(incident);

        service(new FakeSource() {
            @Override
            public IncidentContext load(Incident i, ServiceGraph g, Instant now) {
                throw new IllegalStateException("Zabbix недоступен");
            }
        }).analyze(incident.id());

        Incident failed = incidents.find(incident.id()).orElseThrow();
        assertThat(failed.analysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(failed.analysisError()).contains("Zabbix недоступен");
        assertThat(comments).isEmpty();
    }

    private AnalysisService service(ContextSource source) {
        ZabbixWriter writer = new ZabbixWriter() {
            public void push(String host, String key, Object value) { }

            public void comment(long eventId, String message) {
                comments.add(message);
            }

            public void markSymptom(long eventId, long causeEventId) { }

            public String createTrigger(String d, String e, int p, String c) {
                return "1";
            }

            public void updateTrigger(String id, Map<String, Object> f) { }
        };
        return new AnalysisService(incidents, source, new TagServiceGraphProvider(), new BaselineDeviationDetector(),
                new PatternLogEvidenceExtractor(), new EarliestDeviationRanker(),
                new TemplateExplanationWriter(new StubLlmClient()), new NoCauseSymptomPolicy(), writer,
                new AdvisorProperties(null, null, null, null, null, null), Clock.fixed(START.plusSeconds(120), ZoneOffset.UTC));
    }

    /** gateway → orders → database; задержка database выросла раньше, чем ошибки у остальных. */
    static class FakeSource implements ContextSource {

        @Override
        public List<ServiceGraphProvider.HostInfo> hosts() {
            return CascadeFixture.HOSTS;
        }

        @Override
        public IncidentContext load(Incident incident, ServiceGraph graph, Instant now) {
            var scope = new LinkedHashSet<String>();
            incident.hostNames().forEach(host -> scope.addAll(graph.neighbourhood(host, 2)));
            var window = new TimeWindow(IncidentFixtures.WINDOW.from(), now);
            return context(incident, window, graph, List.copyOf(scope),
                    metrics(window).stream().filter(series -> scope.contains(series.item().host())).toList(),
                    CascadeFixture.logs(window).stream().filter(series -> scope.contains(series.item().host())).toList());
        }

        List<MetricSeries> metrics(TimeWindow window) {
            return CascadeFixture.metrics(window);
        }
    }
}
