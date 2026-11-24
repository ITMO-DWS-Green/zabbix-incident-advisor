package ru.itmo.advisor.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.START;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.fault;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.incident;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.domain.model.AnalysisResult;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.EvaluationReport;
import ru.itmo.advisor.domain.model.FaultLabel;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.Suspect;

class TimeOverlapEvaluatorTest {

    private final TimeOverlapEvaluator evaluator = new TimeOverlapEvaluator();

    @Test
    void countsFirstAndThirdRanksAndExcludesFourthFromTopThree() {
        Instant secondStart = START.plusSeconds(300);
        Instant thirdStart = START.plusSeconds(600);
        var labels = List.of(
                fault("quality", "db-fault", "ERRORS_5XX", "database", START, Duration.ofSeconds(60)),
                fault("quality", "ledger-fault", "LATENCY", "ledger", secondStart, Duration.ofSeconds(60)),
                fault("quality", "cache-fault", "LATENCY", "cache", thirdStart, Duration.ofSeconds(60)));
        var incidents = List.of(
                analyzedIncident(1, START.plusSeconds(10), "database", "orders", "gateway"),
                analyzedIncident(2, secondStart.plusSeconds(10), "billing", "audit", "ledger"),
                analyzedIncident(3, thirdStart.plusSeconds(10), "catalog", "search", "pricing", "cache"));

        EvaluationReport report = evaluator.evaluate(labels, incidents);

        assertThat(report.faults()).isEqualTo(3);
        assertThat(report.matched()).isEqualTo(3);
        assertThat(report.sourceCorrect()).isEqualTo(1);
        assertThat(report.sourceInTop3()).isEqualTo(2);
        assertThat(report.meanTimeToAnswer()).isEqualTo(Duration.ofSeconds(40));
        assertThat(report.rows()).extracting(EvaluationReport.Row::faultId, EvaluationReport.Row::rankOfExpected)
                .containsExactlyInAnyOrder(tuple("db-fault", 1), tuple("ledger-fault", 3), tuple("cache-fault", 4));
    }

    @Test
    @Tag("mvp-target")
    void oneAnalysisCountsForOnlyOneOverlappingFault() {
        Incident database = analyzedIncident(1, START.plusSeconds(15), "database", "orders", "gateway");

        EvaluationReport report = evaluator.evaluate(overlappingFaults(), List.of(database));

        assertThat(report.faults()).isEqualTo(2);
        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.rows()).extracting(EvaluationReport.Row::incidentId)
                .containsExactlyInAnyOrder(database.id().toString(), null);
    }

    @Test
    @Tag("mvp-target")
    void twoOverlappingFaultsAreMatchedToTheirOwnIncidents() {
        Incident database = analyzedIncident(1, START.plusSeconds(15), "database", "orders", "gateway");
        Incident ledger = analyzedIncident(2, START.plusSeconds(25), "ledger", "billing");

        EvaluationReport report = evaluator.evaluate(overlappingFaults(), List.of(database, ledger));

        assertThat(report.matched()).isEqualTo(2);
        assertThat(report.rows()).extracting(EvaluationReport.Row::faultId, EvaluationReport.Row::predictedSource,
                        EvaluationReport.Row::rankOfExpected, EvaluationReport.Row::incidentId)
                .containsExactlyInAnyOrder(tuple("db-fault", "database", 1, database.id().toString()),
                        tuple("ledger-fault", "ledger", 1, ledger.id().toString()));
    }

    private static List<FaultLabel> overlappingFaults() {
        return List.of(fault("overlap", "db-fault", "ERRORS_5XX", "database", START, Duration.ofSeconds(60)),
                fault("overlap", "ledger-fault", "LATENCY", "ledger", START.plusSeconds(10), Duration.ofSeconds(60)));
    }

    private static Incident analyzedIncident(long eventId, Instant startedAt, String... rankedHosts) {
        Incident pending = incident(eventId, rankedHosts[0], "Сработал триггер", startedAt);
        List<Suspect> suspects = Arrays.stream(rankedHosts)
                .map(host -> new Suspect(host, 1, startedAt, List.of())).toList();
        var result = new AnalysisResult(startedAt.plusSeconds(30), List.of(rankedHosts), suspects,
                List.of(), List.of(), null);
        return new Incident(pending.id(), pending.problemEventId(), pending.triggerName(), pending.severity(),
                pending.hosts(), pending.tags(), startedAt, null, pending.status(), AnalysisStatus.DONE, result, null);
    }
}
