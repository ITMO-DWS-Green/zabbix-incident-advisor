package ru.itmo.advisor.app.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.analysis.explain.TemplateExplanationWriter;
import ru.itmo.advisor.analysis.graph.TagServiceGraphProvider;
import ru.itmo.advisor.analysis.logs.NoLogEvidenceExtractor;
import ru.itmo.advisor.analysis.metrics.BaselineDeviationDetector;
import ru.itmo.advisor.analysis.ranking.EarliestDeviationRanker;
import ru.itmo.advisor.analysis.ranking.NoCauseSymptomPolicy;
import ru.itmo.advisor.app.config.AdvisorProperties;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.HostRef;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.IncidentStatus;
import ru.itmo.advisor.domain.model.MetricSeries;
import ru.itmo.advisor.domain.model.MonitoredItem;
import ru.itmo.advisor.domain.model.ServiceGraph;
import ru.itmo.advisor.domain.model.Tag;
import ru.itmo.advisor.domain.model.TimeWindow;
import ru.itmo.advisor.domain.port.ServiceGraphProvider;
import ru.itmo.advisor.domain.port.ZabbixWriter;
import ru.itmo.advisor.llm.StubLlmClient;

/**
 * Конвейер на подделках: каскад «database тормозит → orders и gateway ошибаются».
 * Пример, как тестировать свою бизнес-логику без Zabbix и БД.
 */
class AnalysisServiceTest {

    private static final Instant START = Instant.parse("2026-10-29T12:00:00Z");

    private final InMemoryIncidentRepository incidents = new InMemoryIncidentRepository();
    private final List<String> comments = new ArrayList<>();

    @Test
    void cascadeFromDatabaseIsTracedToDatabaseAndCommentedInZabbix() {
        Incident incident = new Incident(UUID.randomUUID(), 42, "gateway: доля 5xx выше 10%", 3,
                List.of(new HostRef("gateway", "gateway")), List.of(), START, null,
                IncidentStatus.OPEN, AnalysisStatus.PENDING, null, null);
        incidents.createIfAbsent(incident);

        service(new FakeSource()).analyze(incident.id());

        Incident analyzed = incidents.find(incident.id()).orElseThrow();
        assertThat(analyzed.analysisStatus()).isEqualTo(AnalysisStatus.DONE);
        assertThat(analyzed.analysis().topSuspect()).isEqualTo("database");
        assertThat(analyzed.analysis().scope()).contains("gateway", "orders", "database");
        assertThat(comments).singleElement().asString()
                .startsWith(AnalysisService.COMMENT_PREFIX).contains("database");
    }

    @Test
    void failureIsStoredAndDoesNotThrow() {
        Incident incident = new Incident(UUID.randomUUID(), 43, "x", 3, List.of(new HostRef("gateway", "gateway")),
                List.of(), START, null, IncidentStatus.OPEN, AnalysisStatus.PENDING, null, null);
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
                new NoLogEvidenceExtractor(), new EarliestDeviationRanker(),
                new TemplateExplanationWriter(new StubLlmClient()), new NoCauseSymptomPolicy(), writer,
                new AdvisorProperties(null, null, null, null), Clock.fixed(START.plusSeconds(120), ZoneOffset.UTC));
    }

    /** gateway → orders → database; задержка database выросла раньше, чем ошибки у остальных. */
    static class FakeSource implements ContextSource {

        @Override
        public List<ServiceGraphProvider.HostInfo> hosts() {
            return List.of(
                    new ServiceGraphProvider.HostInfo("gateway", "gateway", List.of(new Tag("depends_on", "orders")), List.of()),
                    new ServiceGraphProvider.HostInfo("orders", "orders", List.of(new Tag("depends_on", "database")), List.of()),
                    new ServiceGraphProvider.HostInfo("database", "database", List.of(), List.of()));
        }

        @Override
        public IncidentContext load(Incident incident, ServiceGraph graph, Instant now) {
            List<String> scope = List.copyOf(graph.neighbourhood("gateway", 2));
            return new IncidentContext(incident, new TimeWindow(START.minus(Duration.ofMinutes(15)), now), graph, scope,
                    List.of(series("database", "Средняя задержка", 50, 4000, START.minusSeconds(240)),
                            series("orders", "Доля 5xx", 0.01, 0.6, START.minusSeconds(150)),
                            series("gateway", "Доля 5xx", 0.01, 0.6, START.minusSeconds(60))),
                    List.of());
        }

        private static MetricSeries series(String host, String name, double normal, double bad, Instant breakAt) {
            List<MetricSeries.Point> points = new ArrayList<>();
            for (Instant t = START.minus(Duration.ofMinutes(15)); t.isBefore(START.plusSeconds(120)); t = t.plusSeconds(10)) {
                double noise = Math.sin(t.getEpochSecond()) * normal * 0.02;
                points.add(new MetricSeries.Point(t, (t.isBefore(breakAt) ? normal : bad) + noise));
            }
            return new MetricSeries(new MonitoredItem(host + "-1", host, name, "k", 0, "", List.of()), points);
        }
    }
}
