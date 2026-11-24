package ru.itmo.advisor.analysis.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.START;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.WINDOW;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.incident;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.metricSeries;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.sustainedChange;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.domain.fixtures.IncidentFixtures;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.MetricSeries;
import ru.itmo.advisor.domain.model.ServiceGraph;

class BaselineDeviationDetectorTest {

    private final BaselineDeviationDetector detector = new BaselineDeviationDetector();

    @Test
    void findsStartOfSustainedDeviationOnSingleService() {
        Instant faultStart = START.minusSeconds(90);
        MetricSeries latency = sustainedChange("database", "Задержка", WINDOW, faultStart, 50, 400);

        assertThat(detector.detect(context(List.of(latency)))).singleElement().satisfies(deviation -> {
            assertThat(deviation.host()).isEqualTo("database");
            assertThat(deviation.itemId()).isEqualTo(latency.item().itemId());
            assertThat(deviation.startedAt()).isEqualTo(faultStart);
            assertThat(deviation.expected()).isCloseTo(50, within(1.0));
            assertThat(deviation.actual()).isCloseTo(400, within(1.0));
        });
    }

    @Test
    void noMetricsGiveNoDeviations() {
        assertThat(detector.detect(context(List.of()))).isEmpty();
    }

    @Test
    @Tag("mvp-target")
    void ignoresAnIsolatedSpikeInNormalNoise() {
        Instant spike = START.minusSeconds(90);
        MetricSeries latency = metricSeries("database", "Задержка", WINDOW, Duration.ofSeconds(10),
                t -> t.equals(spike) ? 400 : 50 + Math.sin(t.getEpochSecond()));

        assertThat(detector.detect(context(List.of(latency)))).isEmpty();
    }

    private static IncidentContext context(List<MetricSeries> metrics) {
        return IncidentFixtures.context(incident(1, "database", "database: высокая задержка", START), WINDOW,
                ServiceGraph.empty(), List.of("database"), metrics, List.of());
    }
}
