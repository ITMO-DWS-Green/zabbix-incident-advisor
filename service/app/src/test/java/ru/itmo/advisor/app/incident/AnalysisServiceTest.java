package ru.itmo.advisor.app.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.START;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.context;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.incident;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.analysis.evaluation.TimeOverlapEvaluator;
import ru.itmo.advisor.analysis.explain.TemplateExplanationWriter;
import ru.itmo.advisor.analysis.graph.TagServiceGraphProvider;
import ru.itmo.advisor.analysis.logs.PatternLogEvidenceExtractor;
import ru.itmo.advisor.analysis.metrics.RobustDeviationDetector;
import ru.itmo.advisor.analysis.ranking.GraphEvidenceRanker;
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
import ru.itmo.advisor.domain.port.ExplanationWriter;
import ru.itmo.advisor.domain.port.ServiceGraphProvider;
import ru.itmo.advisor.domain.port.ZabbixWriter;
import ru.itmo.advisor.llm.StubLlmClient;

/**
 * Конвейер на подделках: каскад «database тормозит → orders и gateway ошибаются».
 * Пример, как тестировать свою бизнес-логику без Zabbix и БД.
 */
class AnalysisServiceTest {

    private final InMemoryIncidentRepository incidents = spy(new InMemoryIncidentRepository());
    private final List<String> comments = new ArrayList<>();

    @Test
    void cascadeFromDatabaseIsTracedToDatabaseAndCommentedInZabbix() {
        Incident incident = incident(42, "gateway", "gateway: доля 5xx выше 10%", START);
        incidents.createIfAbsent(incident);

        service(new FakeSource()).analyze(incident.id());

        Incident analyzed = incidents.find(incident.id()).orElseThrow();
        assertThat(analyzed.analysisStatus()).isEqualTo(AnalysisStatus.DONE);
        assertThat(analyzed.analysis().topSuspect()).isEqualTo("database");
        assertThat(analyzed.analysis().deviations()).filteredOn(d -> d.host().equals("database"))
                .singleElement().satisfies(d -> assertThat(d.startedAt()).isEqualTo(START.minusSeconds(240)));
        assertThat(analyzed.analysis().suspects().getFirst().reasons())
                .anyMatch(r -> r.contains("gateway, orders"));
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

    @Test
    void analyzedAtIncludesContextLoadingAndExplanationButNotPublication() {
        Incident incident = incident(45, "gateway", "x", START);
        incidents.createIfAbsent(incident);
        Instant contextTime = START.plusSeconds(120);
        AtomicReference<Instant> time = new AtomicReference<>(contextTime);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> time.get());
        AtomicReference<Instant> receivedContextTime = new AtomicReference<>();
        AtomicReference<Instant> savedCompletion = new AtomicReference<>();
        doAnswer(invocation -> {
            ru.itmo.advisor.domain.model.AnalysisResult result = invocation.getArgument(1);
            savedCompletion.set(result.completedAt());
            time.updateAndGet(t -> t.plusSeconds(7));
            return invocation.callRealMethod();
        }).when(incidents).saveAnalysis(org.mockito.ArgumentMatchers.eq(incident.id()),
                org.mockito.ArgumentMatchers.any());
        ContextSource source = new FakeSource() {
            @Override
            public IncidentContext load(Incident i, ServiceGraph graph, Instant now) {
                receivedContextTime.set(now);
                time.updateAndGet(t -> t.plusSeconds(10));
                return super.load(i, graph, now);
            }
        };
        ExplanationWriter delegate = new TemplateExplanationWriter(new StubLlmClient());
        ExplanationWriter explanationWriter = (context, suspects, deviations, logs) -> {
            var explanation = delegate.write(context, suspects, deviations, logs);
            time.updateAndGet(t -> t.plusSeconds(20));
            return explanation;
        };

        service(source, clock, explanationWriter, () -> time.updateAndGet(t -> t.plusSeconds(30)))
                .analyze(incident.id());

        var analyzed = incidents.find(incident.id()).orElseThrow();
        assertThat(analyzed.analysisStatus()).isEqualTo(AnalysisStatus.DONE);
        assertThat(receivedContextTime.get()).isEqualTo(contextTime);
        assertThat(analyzed.analysis().analyzedAt()).isEqualTo(contextTime.plusSeconds(30));
        assertThat(analyzed.analysis().completedAt()).isEqualTo(contextTime.plusSeconds(30));
        assertThat(savedCompletion.get()).isEqualTo(contextTime.plusSeconds(30));
        assertThat(Duration.between(contextTime, analyzed.analysis().completedAt())).isEqualTo(Duration.ofSeconds(30));
        var fault = IncidentFixtures.fault("scenario", "fault", "LATENCY", "database", START, Duration.ofMinutes(5));
        var report = new TimeOverlapEvaluator().evaluate(List.of(fault), List.of(analyzed));
        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.timeToAnswerObservations()).isEqualTo(1);
        assertThat(report.meanTimeToAnswer()).isEqualTo(Duration.ofSeconds(150));
        assertThat(report.rows().getFirst().timeToAnswer()).isEqualTo(Duration.ofSeconds(150));
        assertThat(comments).hasSize(1);
        assertThat(time.get()).isEqualTo(contextTime.plusSeconds(67));
    }

    private AnalysisService service(ContextSource source) {
        return service(source, Clock.fixed(START.plusSeconds(120), ZoneOffset.UTC),
                new TemplateExplanationWriter(new StubLlmClient()), () -> { });
    }

    private AnalysisService service(ContextSource source, Clock clock, ExplanationWriter explanationWriter,
                                    Runnable onComment) {
        ZabbixWriter writer = new ZabbixWriter() {
            public void push(String host, String key, Object value) { }

            public void comment(long eventId, String message) {
                comments.add(message);
                onComment.run();
            }

            public void markSymptom(long eventId, long causeEventId) { }

            public String createTrigger(String d, String e, int p, String c) {
                return "1";
            }

            public void updateTrigger(String id, Map<String, Object> f) { }
        };
        return new AnalysisService(incidents, source, new TagServiceGraphProvider(), new RobustDeviationDetector(),
                new PatternLogEvidenceExtractor(), new GraphEvidenceRanker(), explanationWriter,
                new NoCauseSymptomPolicy(), writer, new AdvisorProperties(null, null, null, null, null, null), clock);
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
