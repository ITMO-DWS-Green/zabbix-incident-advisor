package ru.itmo.advisor.analysis.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.domain.model.HostRef;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.Problem;
import ru.itmo.advisor.domain.model.ServiceGraph;

/** Объединение проблем в инцидент на карте стенда: gateway → orders → payments → database, orders → database. */
class GraphWindowMergePolicyTest {

    private static final Instant T0 = Instant.parse("2026-10-29T12:00:00Z");
    private static final ServiceGraph STAND = new ServiceGraph(Map.of(
            "gateway", Set.of("orders"),
            "orders", Set.of("payments", "database"),
            "payments", Set.of("database")));

    private final GraphWindowMergePolicy policy = new GraphWindowMergePolicy(Duration.ofSeconds(60));
    private long nextEventId = 1;

    @Test
    void cascadeFromDatabaseIsOneIncident() {
        // cascade-database: задержка на database, следом таймауты у всех, кто от неё зависит
        List<Incident> incidents = group(policy, STAND,
                problem("database", 0), problem("orders", 10), problem("payments", 12), problem("gateway", 20),
                problem("orders", 25));

        assertThat(incidents).singleElement()
                .satisfies(i -> assertThat(i.problems()).hasSize(5))
                .satisfies(i -> assertThat(i.hostNames()).containsExactlyInAnyOrder(
                        "database", "orders", "payments", "gateway"));
    }

    @Test
    void cascadeArrivingOutOfOrderIsStillOneIncident() {
        List<Incident> incidents = group(policy, STAND,
                problem("gateway", 15), problem("database", 0), problem("orders", 8));

        assertThat(incidents).singleElement()
                .satisfies(i -> assertThat(i.problemEventId()).isEqualTo(2))
                .satisfies(i -> assertThat(i.startedAt()).isEqualTo(T0));
    }

    @Test
    void independentFaultLaterOnRelatedServiceIsSecondIncident() {
        // two-incidents: 5xx на database, через минуту — утечка памяти на gateway
        List<Incident> incidents = group(policy, STAND,
                problem("database", 0), problem("orders", 10), problem("gateway", 15),
                problem("gateway", 75));

        assertThat(incidents).hasSize(2);
        assertThat(incidents.get(0).problems()).hasSize(3);
        assertThat(incidents.get(1).hostNames()).containsExactly("gateway");
    }

    @Test
    void crashedSourceWithLateNodataJoinsItsCascade() {
        // database упала: у зависимых сразу 5xx, у неё самой — только «нет ответа больше минуты» через 64 с
        List<Incident> incidents = group(policy, STAND,
                problem("orders", 0), problem("payments", 1), problem("gateway", 4), problem("database", 64));

        assertThat(incidents).singleElement()
                .satisfies(i -> assertThat(i.hostNames()).contains("database"));
    }

    @Test
    void lateCauseNeedsOpenSymptomsAndHasALimit() {
        Problem orders = problem("orders", 0);
        Incident over = Incident.open(UUID.randomUUID(), orders).withProblems(List.of(
                new Problem(orders.eventId(), orders.triggerName(), 3, orders.hosts(), List.of(), orders.startedAt(),
                        T0.plusSeconds(50))));

        assertThat(policy.mergeInto(problem("database", 64), List.of(over), STAND)).isEmpty();

        Incident ongoing = Incident.open(UUID.randomUUID(), problem("orders", 0));
        assertThat(policy.mergeInto(problem("database", 64), List.of(ongoing), STAND)).contains(ongoing);
        assertThat(policy.mergeInto(problem("database", 121), List.of(ongoing), STAND)).isEmpty();
        // поздний сбой на зависимом сервисе или там, где уже есть симптом, — не причина, а отдельный сбой
        assertThat(policy.mergeInto(problem("gateway", 64), List.of(ongoing), STAND)).isEmpty();
        assertThat(policy.mergeInto(problem("orders", 64), List.of(ongoing), STAND)).isEmpty();
    }

    @Test
    void unrelatedServicesAreSeparateEvenAtTheSameTime() {
        ServiceGraph graph = new ServiceGraph(Map.of("shop", Set.of("db"), "billing", Set.of("ledger")));

        assertThat(group(policy, graph, problem("db", 0), problem("ledger", 5))).hasSize(2);
    }

    @Test
    void sameHostMergesWithoutGraph() {
        assertThat(group(policy, ServiceGraph.empty(), problem("database", 0), problem("database", 30)))
                .singleElement().satisfies(i -> assertThat(i.problems()).hasSize(2));
    }

    @Test
    void windowIsConfigurable() {
        Problem[] twoFaults = {problem("database", 0), problem("gateway", 75)};

        assertThat(group(policy, STAND, twoFaults)).hasSize(2);
        assertThat(group(new GraphWindowMergePolicy(Duration.ofMinutes(2)), STAND, twoFaults)).hasSize(1);
        assertThat(group(new GraphWindowMergePolicy(Duration.ZERO), STAND,
                problem("database", 0), problem("database", 1))).hasSize(2);
    }

    /** Как IncidentService: каждая проблема — в найденный открытый инцидент или в новый. */
    private static List<Incident> group(GraphWindowMergePolicy policy, ServiceGraph graph, Problem... problems) {
        List<Incident> incidents = new ArrayList<>();
        for (Problem p : problems) {
            Optional<Incident> target = policy.mergeInto(p, List.copyOf(incidents), graph);
            if (target.isPresent()) {
                incidents.set(incidents.indexOf(target.get()), target.get().withProblem(p));
            } else {
                incidents.add(Incident.open(UUID.randomUUID(), p));
            }
        }
        return incidents;
    }

    private Problem problem(String host, int atSeconds) {
        return new Problem(nextEventId++, host + ": проблема", 3, List.of(new HostRef(host, host)), List.of(),
                T0.plusSeconds(atSeconds), null);
    }
}
