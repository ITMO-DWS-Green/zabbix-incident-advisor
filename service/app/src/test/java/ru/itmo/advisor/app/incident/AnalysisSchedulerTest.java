package ru.itmo.advisor.app.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.app.config.AdvisorProperties;
import ru.itmo.advisor.app.storage.IncidentRepository;

class AnalysisSchedulerTest {

    @Test
    void lateEventOnlyWaitsForRemainingTimeFromTheEvent() {
        Instant now = Instant.parse("2026-11-20T12:00:00Z");
        List<Duration> waits = new ArrayList<>();
        var props = new AdvisorProperties(null, null, null, null, null, null);
        var scheduler = new AnalysisScheduler(mock(AnalysisService.class), mock(IncidentRepository.class), props,
                Clock.fixed(now, ZoneOffset.UTC)) {
            @Override
            public void schedule(UUID incidentId, Duration after) {
                waits.add(after);
            }
        };

        try {
            UUID id = UUID.randomUUID();
            scheduler.scheduleDefault(id, now);
            scheduler.scheduleDefault(id, now.minusSeconds(45));
            scheduler.scheduleDefault(id, now.minusSeconds(90));
            assertThat(waits).containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(15), Duration.ZERO);
        } finally {
            scheduler.destroy();
        }
    }
}
