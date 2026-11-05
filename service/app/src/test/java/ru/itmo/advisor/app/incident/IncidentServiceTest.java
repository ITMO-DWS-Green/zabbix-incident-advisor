package ru.itmo.advisor.app.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.analysis.explain.TemplateExplanationWriter;
import ru.itmo.advisor.analysis.graph.TagServiceGraphProvider;
import ru.itmo.advisor.analysis.logs.NoLogEvidenceExtractor;
import ru.itmo.advisor.analysis.metrics.BaselineDeviationDetector;
import ru.itmo.advisor.analysis.ranking.EarliestDeviationRanker;
import ru.itmo.advisor.analysis.ranking.GraphWindowMergePolicy;
import ru.itmo.advisor.analysis.ranking.NoCauseSymptomPolicy;
import ru.itmo.advisor.app.config.AdvisorProperties;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.HostRef;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.IncidentStatus;
import ru.itmo.advisor.domain.model.Tag;
import ru.itmo.advisor.domain.model.ZabbixEvent;
import ru.itmo.advisor.domain.port.ServiceGraphProvider;
import ru.itmo.advisor.domain.port.ZabbixWriter;
import ru.itmo.advisor.llm.StubLlmClient;

/**
 * Проблемы из коннектора → инциденты на карте стенда test-server:
 * gateway → orders → payments → database, orders → database.
 * События повторяют то, что Zabbix присылает на плейбуках cascade-database и two-incidents.
 */
class IncidentServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-29T12:00:00Z");

    private final InMemoryIncidentRepository incidents = new InMemoryIncidentRepository();
    private final Map<Long, List<String>> comments = new LinkedHashMap<>();
    private final List<UUID> scheduled = new ArrayList<>();
    private final List<AnalysisScheduler> schedulers = new ArrayList<>();

    @AfterEach
    void stopSchedulers() {
        schedulers.forEach(AnalysisScheduler::destroy);
    }

    @Test
    void cascadeDatabasePlaybookGivesOneIncidentAndOneAnalysis() {
        IncidentService service = service(Duration.ofSeconds(60));

        service.onEvents(List.of(
                problem(101, "database", "database: задержка выше 1 с", 20),
                problem(102, "orders", "orders: доля 5xx выше 10%", 28)));
        service.onEvents(List.of(
                problem(103, "payments", "payments: задержка выше 1 с", 30),
                problem(104, "gateway", "gateway: доля 5xx выше 10%", 33),
                problem(105, "orders", "orders: задержка выше 1 с", 41)));

        assertThat(incidents.findAll()).singleElement().satisfies(i -> {
            assertThat(i.problemEventIds()).containsExactly(101L, 102L, 103L, 104L, 105L);
            assertThat(i.problemEventId()).isEqualTo(101);
            assertThat(i.hostNames()).containsExactlyInAnyOrder("database", "orders", "payments", "gateway");
        });
        assertThat(scheduled).hasSize(1);
    }

    @Test
    void twoIncidentsPlaybookGivesTwoIncidents() {
        IncidentService service = service(Duration.ofSeconds(60));

        // 5xx на database в 60 с — каскад; утечка памяти на gateway в 120 с — независимый сбой
        service.onEvents(List.of(
                problem(201, "database", "database: доля 5xx выше 10%", 80),
                problem(202, "orders", "orders: доля 5xx выше 10%", 85),
                problem(203, "gateway", "gateway: доля 5xx выше 10%", 90),
                problem(204, "gateway", "gateway: память выше 90%", 160)));

        List<Incident> all = incidents.findAll();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).problemEventIds()).containsExactly(201L, 202L, 203L);
        assertThat(all.get(1).problemEventIds()).containsExactly(204L);
        assertThat(scheduled).hasSize(2);
    }

    @Test
    void mergeWindowIsTakenFromSettings() {
        IncidentService wide = service(Duration.ofMinutes(5));
        wide.onEvents(List.of(problem(301, "database", "db", 0), problem(302, "gateway", "gw", 200)));
        assertThat(incidents.findAll()).hasSize(1);

        incidents.byId.clear();
        IncidentService off = service(Duration.ZERO);
        off.onEvents(List.of(problem(311, "database", "db", 0), problem(312, "database", "db 2", 1)));
        assertThat(incidents.findAll()).hasSize(2);
    }

    @Test
    void repeatedDeliveryDoesNotDuplicateProblem() {
        IncidentService service = service(Duration.ofSeconds(60));
        service.onEvents(List.of(problem(401, "database", "db", 0), problem(402, "orders", "orders", 5)));
        service.onEvents(List.of(problem(402, "orders", "orders", 5)));

        assertThat(incidents.findAll()).singleElement()
                .satisfies(i -> assertThat(i.problemEventIds()).containsExactly(401L, 402L));
    }

    @Test
    void incidentIsResolvedWhenAllItsProblemsAre() {
        IncidentService service = service(Duration.ofSeconds(60));
        service.onEvents(List.of(problem(501, "database", "db", 0), problem(502, "orders", "orders", 5)));

        service.onEvents(List.of(recovery(501, 100)));
        assertThat(incidents.findAll()).singleElement().satisfies(i -> {
            assertThat(i.status()).isEqualTo(IncidentStatus.OPEN);
            assertThat(i.resolvedAt()).isNull();
        });

        service.onEvents(List.of(recovery(502, 130)));
        assertThat(incidents.findAll()).singleElement().satisfies(i -> {
            assertThat(i.status()).isEqualTo(IncidentStatus.RESOLVED);
            assertThat(i.resolvedAt()).isEqualTo(T0.plusSeconds(130));
        });
    }

    @Test
    void analysisRunsOnceAndCommentsEveryProblem() {
        IncidentService service = service(Duration.ofSeconds(60));
        service.onEvents(List.of(problem(601, "gateway", "gateway: доля 5xx", 0),
                problem(602, "orders", "orders: доля 5xx", 3)));
        UUID id = incidents.findAll().get(0).id();

        analysis().analyze(id);

        assertThat(incidents.find(id).orElseThrow().analysisStatus()).isEqualTo(AnalysisStatus.DONE);
        assertThat(comments).containsOnlyKeys(601L, 602L);
        assertThat(comments.values()).allSatisfy(c -> assertThat(c).singleElement().asString()
                .startsWith(AnalysisService.COMMENT_PREFIX).contains("database"));
    }

    @Test
    void problemJoiningAnalyzedIncidentGetsExistingAnalysisWithoutNewRun() {
        IncidentService service = service(Duration.ofSeconds(60));
        service.onEvents(List.of(problem(701, "gateway", "gateway: доля 5xx", 0)));
        UUID id = incidents.findAll().get(0).id();
        analysis().analyze(id);

        service.onEvents(List.of(problem(702, "database", "database: задержка", 40)));

        assertThat(incidents.find(id).orElseThrow().problemEventIds()).containsExactly(701L, 702L);
        assertThat(comments).containsOnlyKeys(701L, 702L);
        assertThat(comments.get(702L)).singleElement().asString().startsWith(AnalysisService.COMMENT_PREFIX);
        assertThat(scheduled).hasSize(1);
    }

    private IncidentService service(Duration mergeWindow) {
        AnalysisService analysis = analysis();
        AnalysisScheduler scheduler = new AnalysisScheduler(analysis, incidents, props()) {
            @Override
            public void scheduleDefault(UUID incidentId) {
                scheduled.add(incidentId);
            }
        };
        schedulers.add(scheduler);
        return new IncidentService(incidents, scheduler, analysis, new GraphWindowMergePolicy(mergeWindow),
                new ServiceGraphCache(new TagServiceGraphProvider(), new StandSource(),
                        Clock.fixed(T0, ZoneOffset.UTC)));
    }

    private AnalysisService analysis() {
        ZabbixWriter writer = new ZabbixWriter() {
            public void push(String host, String key, Object value) { }

            public void comment(long eventId, String message) {
                comments.computeIfAbsent(eventId, e -> new ArrayList<>()).add(message);
            }

            public void markSymptom(long eventId, long causeEventId) { }

            public String createTrigger(String d, String e, int p, String c) {
                return "1";
            }

            public void updateTrigger(String id, Map<String, Object> f) { }
        };
        return new AnalysisService(incidents, new StandSource(), new TagServiceGraphProvider(),
                new BaselineDeviationDetector(), new NoLogEvidenceExtractor(), new EarliestDeviationRanker(),
                new TemplateExplanationWriter(new StubLlmClient()), new NoCauseSymptomPolicy(), writer, props(),
                Clock.fixed(T0.plusSeconds(120), ZoneOffset.UTC));
    }

    private static AdvisorProperties props() {
        return new AdvisorProperties(null, null, null, null, null);
    }

    /** Карта стенда и данные каскада из {@link AnalysisServiceTest}. */
    static class StandSource extends AnalysisServiceTest.FakeSource {
        @Override
        public List<ServiceGraphProvider.HostInfo> hosts() {
            return List.of(
                    host("gateway", "orders"),
                    host("orders", "payments,database"),
                    host("payments", "database"),
                    host("database", null));
        }

        private static ServiceGraphProvider.HostInfo host(String name, String dependsOn) {
            return new ServiceGraphProvider.HostInfo(name, name,
                    dependsOn == null ? List.of() : List.of(new Tag("depends_on", dependsOn)), List.of());
        }
    }

    private static ZabbixEvent problem(long eventId, String host, String name, int atSeconds) {
        Instant t = T0.plusSeconds(atSeconds);
        return new ZabbixEvent(t.getEpochSecond(), 0, 1, eventId, null, name, 3, List.of(new HostRef(host, host)),
                List.of("test-server"), List.of(new Tag("service", host)));
    }

    private static ZabbixEvent recovery(long problemEventId, int atSeconds) {
        Instant t = T0.plusSeconds(atSeconds);
        return new ZabbixEvent(t.getEpochSecond(), 0, 0, problemEventId + 10_000, problemEventId, null, null,
                null, null, null);
    }
}
