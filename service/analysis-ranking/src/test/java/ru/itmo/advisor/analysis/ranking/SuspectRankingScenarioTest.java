package ru.itmo.advisor.analysis.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.START;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.WINDOW;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.context;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.incident;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.domain.fixtures.CascadeFixture;
import ru.itmo.advisor.domain.model.LogEvidence;
import ru.itmo.advisor.domain.model.MetricDeviation;
import ru.itmo.advisor.domain.model.Suspect;

class SuspectRankingScenarioTest {

    @Test
    void ranksFaultyDependencyAboveSlightlyEarlierSymptom() {
        var context = context(incident(1, "gateway", "gateway: доля 5xx выше 10%", START), WINDOW,
                CascadeFixture.GRAPH, List.of("gateway", "orders", "database"), List.of(), List.of());
        Instant faultStart = START.minusSeconds(60);
        var deviations = List.of(
                new MetricDeviation("gateway", "gateway-1", "Доля 5xx", "", 0.01, 0.3,
                        faultStart.minusSeconds(10), 8),
                new MetricDeviation("database", "database-1", "Средняя задержка", "ms", 50, 4000,
                        faultStart, 20),
                new MetricDeviation("orders", "orders-1", "Доля 5xx", "", 0.01, 0.6,
                        faultStart.plusSeconds(10), 10));
        var logs = List.of(new LogEvidence("database", "database-log", "Deadlock detected",
                "Unhandled exception: Deadlock detected", 3, faultStart, true),
                new LogEvidence("orders", "orders-log", "downstream database responded 500",
                        "downstream database responded 500", 3, faultStart.plusSeconds(10), true));

        List<Suspect> suspects = new GraphEvidenceRanker().rank(context, deviations, logs);

        assertThat(suspects).first().satisfies(suspect -> {
            assertThat(suspect.host()).isEqualTo("database");
            assertThat(suspect.reasons()).anySatisfy(reason -> assertThat(reason).contains("orders"));
        });
    }
}
