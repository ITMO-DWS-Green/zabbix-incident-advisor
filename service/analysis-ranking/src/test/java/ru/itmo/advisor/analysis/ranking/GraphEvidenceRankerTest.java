package ru.itmo.advisor.analysis.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.domain.model.*;

class GraphEvidenceRankerTest {
    private static final Instant START = Instant.parse("2026-11-20T12:00:00Z");
    private static final ServiceGraph CASCADE = new ServiceGraph(Map.of(
            "gateway", Set.of("orders"), "orders", Set.of("database")));
    private final GraphEvidenceRanker ranker = new GraphEvidenceRanker();

    @Test
    void dependencyExplainsCascadeEvenWhenSymptomIsSampledFirst() {
        var result = ranker.rank(context(CASCADE, List.of("gateway", "orders", "database")),
                List.of(metric("gateway", 0, 100), metric("orders", 10, 80), metric("database", 30, 5)), List.of());
        assertThat(result.getFirst().host()).isEqualTo("database");
        assertThat(result.getFirst().reasons()).anyMatch(r -> r.contains("gateway, orders"));
        assertThat(result).allSatisfy(s -> assertThat(s.score()).isBetween(0.0, 1.0));
        assertThat(result.stream().filter(s -> s.host().equals("orders")).findFirst().orElseThrow().reasons())
                .anyMatch(r -> r.contains("симптом") && r.contains("database"));
    }

    @Test
    void olderLatencyNoiseDoesNotHideTheSourceOfNewCascadeErrors() {
        var result = ranker.rank(context(CASCADE, List.of("gateway", "orders", "database")),
                List.of(metric("orders", -180, 4), metric("orders", 0, 1e9),
                        metric("gateway", 0, 1e9), metric("database", 10, 1e9)),
                List.of(log("orders", 120, true), log("gateway", 120, true), log("database", 120, true)));
        assertThat(result.getFirst().host()).isEqualTo("database");
        assertThat(result.getFirst().reasons()).anyMatch(r -> r.contains("gateway, orders"));
        assertThat(result.stream().filter(s -> s.host().equals("orders")).findFirst().orElseThrow())
                .satisfies(s -> {
                    assertThat(s.firstDeviationAt()).isEqualTo(START.minusSeconds(180));
                    assertThat(s.reasons()).anyMatch(r -> r.contains("симптом") && r.contains("database"));
                });
    }

    @Test
    void newLogOnsetCanLinkACascadeDespiteAnOlderMetricOnTheCaller() {
        var result = ranker.rank(context(new ServiceGraph(Map.of("orders", Set.of("database"))),
                        List.of("orders", "database")),
                List.of(metric("orders", -180, 4), metric("database", 0, 10)),
                List.of(log("orders", 120, true), log("database", 120, true)));
        assertThat(result.getFirst().host()).isEqualTo("database");
    }

    @Test
    void muchLaterDependencyDoesNotExplainEarlierSymptom() {
        var result = ranker.rank(context(new ServiceGraph(Map.of("orders", Set.of("database"))),
                        List.of("orders", "database")),
                List.of(metric("orders", 0, 10), metric("database", 300, 10)), List.of());
        assertThat(result.getFirst().host()).isEqualTo("orders");
        assertThat(result.stream().filter(s -> s.host().equals("database")).findFirst().orElseThrow().reasons())
                .noneMatch(r -> r.contains("распространением"));
    }

    @Test
    void twoIndependentIncidentsFindTheirOwnSources() {
        ServiceGraph graph = new ServiceGraph(Map.of("orders", Set.of("database"), "search", Set.of("index")));
        var all = List.of(metric("database", 0, 10), metric("orders", 20, 10),
                metric("index", 200, 10), metric("search", 220, 10));
        assertThat(ranker.rank(context(graph, List.of("orders", "database")), all, List.of()).getFirst().host())
                .isEqualTo("database");
        assertThat(ranker.rank(context(graph, List.of("search", "index")), all, List.of()).getFirst().host())
                .isEqualTo("index");
        var combined = ranker.rank(context(graph, List.of("orders", "database", "search", "index")), all, List.of());
        assertThat(combined.subList(0, 2)).extracting(Suspect::host).containsExactly("database", "index");
        assertThat(combined.get(0).score()).isEqualTo(combined.get(1).score());
    }

    @Test
    void graphAloneCannotAccuseAHealthyDependency() {
        var result = ranker.rank(context(CASCADE, List.of("gateway", "orders", "database")),
                List.of(metric("orders", 0, 10)), List.of());
        assertThat(result).extracting(Suspect::host).containsExactly("orders");
    }

    @Test
    void strongerDeviationAndNewLogEvidenceIncreaseScoreWithConcreteReasons() {
        var context = context(ServiceGraph.empty(), List.of("a", "b"));
        var metricOnly = ranker.rank(context, List.of(metric("a", 0, 5), metric("b", 0, 20)), List.of());
        assertThat(metricOnly.getFirst().host()).isEqualTo("b");
        var result = ranker.rank(context, List.of(metric("a", 0, 5), metric("b", 0, 20)),
                List.of(log("a", 120, true)));
        assertThat(result.getFirst().host()).isEqualTo("a");
        assertThat(result.getFirst().reasons()).anyMatch(r -> r.contains("120") && r.contains("connection refused"));
    }

    @Test
    void logOnlySourceCanExplainDependentMetrics() {
        var result = ranker.rank(context(CASCADE, List.of("gateway", "orders", "database")),
                List.of(metric("orders", 10, 10), metric("gateway", 20, 10)), List.of(log("database", 120, true)));
        assertThat(result.getFirst().host()).isEqualTo("database");
    }

    @Test
    void oldLogPatternDoesNotPretendToDateItsFrequencyIncrease() {
        var old = new LogEvidence("a", "1", "connection refused", "example", 100, START.minusSeconds(600), false);
        var result = ranker.rank(context(ServiceGraph.empty(), List.of("a")), List.of(), List.of(old));
        assertThat(result.getFirst().firstDeviationAt()).isEqualTo(START);
    }

    @Test
    void cyclesAndInputOrderingDoNotInventDirectionalEvidence() {
        var graph = new ServiceGraph(Map.of("a", Set.of("b"), "b", Set.of("a")));
        var context = context(graph, List.of("a", "b"));
        var result = ranker.rank(context, List.of(metric("b", 0, 10), metric("a", 0, 10)), List.of());
        assertThat(result).isEqualTo(ranker.rank(context, List.of(metric("a", 0, 10), metric("b", 0, 10)), List.of()));
        assertThat(result).extracting(Suspect::host).containsExactly("a", "b");
        assertThat(result).allSatisfy(s -> assertThat(s.reasons()).noneMatch(r -> r.contains("распространением")));
    }

    @Test
    void noEvidenceUsesExplicitLowConfidenceFallback() {
        var result = ranker.rank(context(ServiceGraph.empty(), List.of("gateway")),
                List.of(metric("gateway", 0, Double.NaN), metric("unknown", 0, 10)), List.of());
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().score()).isEqualTo(0.1);
        assertThat(result.getFirst().reasons()).containsExactly("Хост проблемы; подтверждений в метриках и логах нет");
    }

    @Test
    void dataOutsideWindowDoesNotAffectRanking() {
        var result = ranker.rank(context(ServiceGraph.empty(), List.of("gateway")),
                List.of(metric("gateway", 900, 10)), List.of(new LogEvidence("gateway", "1", "x", "x", 5,
                        START.minusSeconds(1000), true)));
        assertThat(result.getFirst().score()).isEqualTo(0.1);
    }

    private static MetricDeviation metric(String host, long seconds, double strength) {
        return new MetricDeviation(host, host + "-1", "latency", "ms", 100, 500, START.plusSeconds(seconds), strength);
    }

    private static LogEvidence log(String host, int count, boolean fresh) {
        return new LogEvidence(host, "1", "connection refused", "connection refused", count, START, fresh);
    }

    private static IncidentContext context(ServiceGraph graph, List<String> scope) {
        Incident incident = new Incident(UUID.randomUUID(), 1, "test", 3, List.of(new HostRef(scope.getFirst(), "test")),
                List.of(), START, null, IncidentStatus.OPEN, AnalysisStatus.PENDING, null, null);
        return new IncidentContext(incident, new TimeWindow(START.minusSeconds(900), START.plusSeconds(600)),
                graph, scope, List.of(), List.of());
    }
}
