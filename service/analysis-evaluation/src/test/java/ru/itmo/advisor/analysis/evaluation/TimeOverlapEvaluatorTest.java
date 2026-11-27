package ru.itmo.advisor.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import ru.itmo.advisor.domain.model.EvaluationReport.Candidate;
import ru.itmo.advisor.domain.model.EvaluationReport.MatchStatus;
import ru.itmo.advisor.domain.model.FaultLabel;
import ru.itmo.advisor.domain.model.HostRef;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.Problem;
import ru.itmo.advisor.domain.model.Suspect;
import ru.itmo.advisor.domain.model.TimeWindow;

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
        // analyzedAt in stored results has no provenance proving that analysis was complete.
        assertThat(report.meanTimeToAnswer()).isNull();
        assertThat(report.rows()).extracting(EvaluationReport.Row::faultId, EvaluationReport.Row::rankOfExpected)
                .containsExactlyInAnyOrder(tuple("db-fault", 1), tuple("ledger-fault", 3), tuple("cache-fault", 4));
    }

    @Test
    void missingOrBlankExpectedSourceNeverMatchesEvenMissingOrBlankSuspectHost() {
        for (String expected : Arrays.asList(null, "", " ", "\t")) {
            for (String host : Arrays.asList(null, "", " ", "\t", "database")) {
                FaultLabel label = fault("s", "f", "type", expected, START, Duration.ofSeconds(60));
                Incident pending = incident(1, "database", "trigger", START.plusSeconds(10));
                var analysis = new AnalysisResult(START.plusSeconds(30), List.of(),
                        List.of(new Suspect(host, 1, START, List.of())), List.of(), List.of(), null);
                Incident analyzed = new Incident(pending.id(), pending.problemEventId(), pending.triggerName(),
                        pending.severity(), pending.hosts(), pending.tags(), pending.startedAt(), pending.resolvedAt(),
                        pending.status(), AnalysisStatus.DONE, analysis, null);

                EvaluationReport report = evaluator.evaluate(List.of(label), List.of(analyzed));
                var row = report.rows().get(0);
                assertThat(report.matched()).isEqualTo(1);
                assertThat(report.sourceCorrect()).isZero();
                assertThat(report.sourceInTop3()).isZero();
                assertThat(row.matchStatus()).isEqualTo(MatchStatus.MATCHED);
                assertThat(row.rankOfExpected()).as("expected=%s host=%s", expected, host).isNull();
                assertThat(row.candidates()).extracting(Candidate::rankOfExpected).containsExactly((Integer) null);
            }
        }
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

    @Test
    void sharedIncidentMakesBothFaultsAmbiguousWithoutSelectingByExpectedSource() {
        Incident database = analyzedIncident(1, START.plusSeconds(15), "database", "ledger");
        EvaluationReport report = evaluator.evaluate(overlappingFaults(), List.of(database));

        assertThat(report.faults()).isEqualTo(2);
        assertThat(report.matched()).isZero();
        assertThat(report.sourceCorrect()).isZero();
        assertThat(report.sourceInTop3()).isZero();
        assertThat(report.meanTimeToAnswer()).isNull();
        assertThat(report.rows()).allSatisfy(row -> {
            assertThat(row.matchStatus()).isEqualTo(MatchStatus.AMBIGUOUS);
            assertThat(row.incidentId()).isNull();
            assertThat(row.predictedSource()).isNull();
            assertThat(row.candidates()).extracting(Candidate::incidentId).containsExactly(database.id().toString());
            assertThat(row.candidates().get(0).ambiguous()).isTrue();
        });
    }

    @Test
    void allCandidatesAreReportedButOnlyFirstUnambiguousAnalysisCounts() {
        FaultLabel label = fault("s", "f", "type", "database", START, Duration.ofSeconds(60));
        Incident first = analyzedIncident(1, START.plusSeconds(10), "other", "second", "third", "database");
        Incident second = analyzedIncident(2, START.plusSeconds(20), "database");
        Incident pending = incident(3, "other", "pending", START.plusSeconds(30));
        EvaluationReport report = evaluator.evaluate(List.of(label), List.of(pending, second, first));

        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.sourceCorrect()).isZero();
        assertThat(report.sourceInTop3()).isZero();
        var row = report.rows().get(0);
        assertThat(row.matchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(row.incidentId()).isEqualTo(first.id().toString());
        assertThat(row.predictedSource()).isEqualTo("other");
        assertThat(row.rankOfExpected()).isEqualTo(4);
        assertThat(row.candidates()).extracting(Candidate::incidentId, Candidate::analyzed, Candidate::rankOfExpected)
                .containsExactly(tuple(first.id().toString(), true, 4), tuple(second.id().toString(), true, 1),
                        tuple(pending.id().toString(), false, null));
    }

    @Test
    void retainedAnalysisIsIgnoredUntilDoneAndNextDoneCandidateIsSelected() {
        FaultLabel label = fault("s", "f", "type", "database", START, Duration.ofSeconds(60));
        Incident old = completedIncident(1, START.plusSeconds(10), START.plusSeconds(15));
        Incident done = completedIncident(2, START.plusSeconds(20), START.plusSeconds(40));

        for (AnalysisStatus status : List.of(AnalysisStatus.PENDING, AnalysisStatus.RUNNING, AnalysisStatus.FAILED)) {
            Incident retained = new Incident(old.id(), old.problemEventId(), old.triggerName(), old.severity(),
                    old.hosts(), old.tags(), old.startedAt(), old.resolvedAt(), old.status(), status, old.analysis(),
                    old.analysisError(), old.problems());
            var withoutDone = evaluator.evaluate(List.of(label), List.of(retained));
            var unmatched = withoutDone.rows().get(0);
            assertThat(withoutDone.matched()).as(status.name()).isZero();
            assertThat(withoutDone.sourceCorrect()).isZero();
            assertThat(withoutDone.timeToAnswerObservations()).isZero();
            assertThat(withoutDone.baselineObservations()).isZero();
            assertThat(unmatched.matchStatus()).isEqualTo(MatchStatus.NO_ANALYSIS);
            assertThat(unmatched.incidentId()).isNull();
            assertThat(unmatched.predictedSource()).isNull();
            assertThat(unmatched.rankOfExpected()).isNull();
            assertThat(unmatched.candidates()).extracting(Candidate::incidentId, Candidate::analyzed,
                            Candidate::predictedSource, Candidate::rankOfExpected)
                    .containsExactly(tuple(retained.id().toString(), false, null, null));

            var report = evaluator.evaluate(List.of(label), List.of(done, retained));
            var row = report.rows().get(0);
            assertThat(report.matched()).isEqualTo(1);
            assertThat(report.sourceCorrect()).isEqualTo(1);
            assertThat(row.matchStatus()).isEqualTo(MatchStatus.MATCHED);
            assertThat(row.incidentId()).isEqualTo(done.id().toString());
            assertThat(row.predictedSource()).isEqualTo("database");
            assertThat(row.rankOfExpected()).isEqualTo(1);
            assertThat(row.timeToAnswer()).isEqualTo(Duration.ofSeconds(40));
            assertThat(row.candidates()).extracting(Candidate::incidentId, Candidate::analyzed,
                            Candidate::predictedSource, Candidate::rankOfExpected)
                    .containsExactly(tuple(retained.id().toString(), false, null, null),
                            tuple(done.id().toString(), true, "database", 1));
        }
    }

    @Test
    void doneWithoutAnalysisIsNotAnalyzed() {
        FaultLabel label = fault("s", "f", "type", "database", START, Duration.ofSeconds(60));
        Incident pending = incident(1, "database", "pending", START.plusSeconds(10));
        Incident doneWithoutAnalysis = new Incident(pending.id(), pending.problemEventId(), pending.triggerName(),
                pending.severity(), pending.hosts(), pending.tags(), pending.startedAt(), pending.resolvedAt(),
                pending.status(), AnalysisStatus.DONE, null, null, pending.problems());

        var row = evaluator.evaluate(List.of(label), List.of(doneWithoutAnalysis)).rows().get(0);
        assertThat(row.matchStatus()).isEqualTo(MatchStatus.NO_ANALYSIS);
        assertThat(row.candidates()).extracting(Candidate::analyzed, Candidate::predictedSource, Candidate::rankOfExpected)
                .containsExactly(tuple(false, null, null));
    }

    @Test
    void sharedPendingIncidentDoesNotExcludeAnUnambiguousAnalysis() {
        Incident shared = incident(1, "database", "pending", START.plusSeconds(15));
        Incident unique = analyzedIncident(2, START.plusSeconds(5), "database");
        EvaluationReport report = evaluator.evaluate(overlappingFaults(), List.of(shared, unique));

        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.sourceCorrect()).isEqualTo(1);
        assertThat(report.sourceInTop3()).isEqualTo(1);
        assertThat(report.rows()).extracting(EvaluationReport.Row::matchStatus)
                .containsExactly(MatchStatus.MATCHED, MatchStatus.AMBIGUOUS);
        assertThat(report.rows().get(0).incidentId()).isEqualTo(unique.id().toString());
        assertThat(report.rows().get(0).candidates()).extracting(Candidate::ambiguous).containsExactly(false, true);
    }

    @Test
    void sharedCorrectAnalysisCannotOverrideUniqueWrongAnalysisOrSucceedAlone() {
        Incident shared = analyzedIncident(1, START.plusSeconds(15), "database", "ledger");
        Incident unique = analyzedIncident(2, START.plusSeconds(5), "wrong");
        FaultLabel first = fault("s", "first", "type", "database", START, Duration.ofSeconds(60));
        FaultLabel second = fault("s", "second", "type", "ledger", START.plusSeconds(10), Duration.ofSeconds(5));
        EvaluationReport report = evaluator.evaluate(List.of(first, second), List.of(shared, unique));

        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.sourceCorrect()).isZero();
        assertThat(report.sourceInTop3()).isZero();
        assertThat(report.rows()).extracting(EvaluationReport.Row::matchStatus)
                .containsExactly(MatchStatus.MATCHED, MatchStatus.AMBIGUOUS);
        var row = report.rows().get(0);
        assertThat(row.incidentId()).isEqualTo(unique.id().toString());
        assertThat(row.predictedSource()).isEqualTo("wrong");
        assertThat(row.candidates()).extracting(Candidate::incidentId, Candidate::ambiguous, Candidate::rankOfExpected)
                .containsExactly(tuple(unique.id().toString(), false, null), tuple(shared.id().toString(), true, 1));
    }

    @Test
    void earlierSharedAnalysisIsSkippedForLaterUniqueAnalysis() {
        Incident shared = analyzedIncident(1, START.plusSeconds(15), "database", "ledger");
        Incident unique = analyzedIncident(2, START.plusSeconds(121), "wrong");
        FaultLabel first = fault("s", "first", "type", "database", START, Duration.ofSeconds(60));
        FaultLabel second = fault("s", "second", "type", "ledger", START, Duration.ZERO);
        // The second window includes the shared incident but ends before the unique incident.
        EvaluationReport report = evaluator.evaluate(List.of(first, second), List.of(unique, shared));

        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.sourceCorrect()).isZero();
        assertThat(report.sourceInTop3()).isZero();
        var row = report.rows().stream().filter(r -> r.faultId().equals("first")).findFirst().orElseThrow();
        assertThat(row.incidentId()).isEqualTo(unique.id().toString());
        assertThat(row.candidates()).extracting(Candidate::incidentId, Candidate::ambiguous)
                .containsExactly(tuple(shared.id().toString(), true), tuple(unique.id().toString(), false));
    }

    @Test
    void includesUnmatchedAndUnanalyzedFaultsAndUsesHalfOpenGraceWindow() {
        FaultLabel label = fault("s", "f", "type", "database", START, Duration.ofSeconds(60));
        Incident before = incident(1, "x", "before", START.minusNanos(1));
        Incident atStart = incident(2, "x", "start", START);
        Incident beforeEnd = incident(3, "x", "end", START.plusSeconds(180).minusNanos(1));
        Incident atEnd = incident(4, "x", "end", START.plusSeconds(180));
        EvaluationReport report = evaluator.evaluate(List.of(label), List.of(before, atStart, beforeEnd, atEnd));
        assertThat(report.rows().get(0).matchStatus()).isEqualTo(MatchStatus.NO_ANALYSIS);
        assertThat(report.rows().get(0).candidates()).extracting(Candidate::incidentId)
                .containsExactly(atStart.id().toString(), beforeEnd.id().toString());
        assertThat(report.matched()).isZero();
        assertThat(evaluator.evaluate(List.of(label), List.of()).rows().get(0).matchStatus())
                .isEqualTo(MatchStatus.NO_INCIDENT);
    }

    @Test
    void outsideIncidentsAreReportedOnlyInExplicitObservationWindow() {
        FaultLabel label = fault("s", "f", "type", "database", START, Duration.ofSeconds(60));
        Incident before = incident(1, "x", "before", START.minusSeconds(10));
        Incident inside = incident(2, "x", "inside", START);
        Incident atEnd = incident(3, "x", "end", START.plusSeconds(180));
        Incident observationEnd = incident(4, "x", "outside", START.plusSeconds(200));
        List<Incident> incidents = List.of(observationEnd, atEnd, inside, before);
        assertThat(evaluator.evaluate(List.of(label), incidents).outsideWindowIncidentIds()).isNull();
        EvaluationReport report = evaluator.evaluate(List.of(label), incidents,
                new TimeWindow(START.minusSeconds(10), START.plusSeconds(200)));
        assertThat(report.outsideWindowIncidentIds()).containsExactly(before.id().toString(), atEnd.id().toString());
        assertThatThrownBy(() -> evaluator.evaluate(List.of(label), incidents, new TimeWindow(START, START)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void observationWindowRestrictsCandidatesAndOutsideAtBothBoundaries() {
        FaultLabel label = fault("s", "f", "type", "database", START, Duration.ofSeconds(60));
        Instant from = START.plusSeconds(10);
        Instant to = START.plusSeconds(40);
        Incident before = analyzedIncident(1, from.minusNanos(1), "database");
        Incident atFrom = analyzedIncident(2, from, "wrong");
        Incident beforeTo = analyzedIncident(3, to.minusNanos(1), "database");
        Incident atTo = analyzedIncident(4, to, "database");
        Incident outsideBefore = incident(5, "x", "outside", START.minusSeconds(1));
        Incident outsideWithin = incident(6, "x", "outside", START.plusSeconds(200));
        TimeWindow window = new TimeWindow(from, to);
        EvaluationReport report = evaluator.evaluate(List.of(label),
                List.of(atTo, beforeTo, outsideWithin, before, atFrom, outsideBefore), window);

        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.sourceCorrect()).isZero();
        assertThat(report.sourceInTop3()).isZero();
        assertThat(report.rows().get(0).incidentId()).isEqualTo(atFrom.id().toString());
        assertThat(report.rows().get(0).candidates()).extracting(Candidate::incidentId)
                .containsExactly(atFrom.id().toString(), beforeTo.id().toString());
        assertThat(report.outsideWindowIncidentIds()).isEmpty();

        EvaluationReport outsideReport = evaluator.evaluate(List.of(label),
                List.of(outsideBefore, atFrom, outsideWithin),
                new TimeWindow(START.minusSeconds(1), START.plusSeconds(200)));
        assertThat(outsideReport.outsideWindowIncidentIds()).containsExactly(outsideBefore.id().toString());
    }

    @Test
    void observationWindowExcludesAllMatchingIncidentsOutsideIt() {
        FaultLabel label = fault("s", "f", "type", "database", START, Duration.ofSeconds(60));
        Incident before = analyzedIncident(1, START.plusSeconds(9), "database");
        Incident atEnd = analyzedIncident(2, START.plusSeconds(20), "database");
        EvaluationReport report = evaluator.evaluate(List.of(label), List.of(before, atEnd),
                new TimeWindow(START.plusSeconds(10), START.plusSeconds(20)));

        assertThat(report.matched()).isZero();
        assertThat(report.rows().get(0).matchStatus()).isEqualTo(MatchStatus.NO_INCIDENT);
        assertThat(report.rows().get(0).candidates()).isEmpty();
        assertThat(report.outsideWindowIncidentIds()).isEmpty();
    }

    @Test
    void resultsAreIndependentOfInputOrderIncludingEqualIncidentTimes() {
        var labels = overlappingFaults();
        Incident first = analyzedIncident(1, START.plusSeconds(15), "database");
        Incident second = analyzedIncident(2, START.plusSeconds(15), "ledger");
        assertThat(evaluator.evaluate(labels, List.of(second, first)))
                .isEqualTo(evaluator.evaluate(List.of(labels.get(1), labels.get(0)), List.of(first, second)));
        assertThat(evaluator.evaluate(labels, List.of(second, first)).rows().get(0).candidates())
                .extracting(Candidate::incidentId).containsExactly(first.id().toString(), second.id().toString());
        assertThatThrownBy(() -> evaluator.evaluate(labels, List.of(first, first)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotDeduplicateFaultIdsAcrossScenarios() {
        FaultLabel first = fault("one", "same", "type", "database", START, Duration.ofSeconds(30));
        FaultLabel second = fault("two", "same", "type", "database", START, Duration.ofSeconds(30));
        Incident shared = analyzedIncident(1, START, "database");
        EvaluationReport report = evaluator.evaluate(List.of(second, first), List.of(shared));
        assertThat(report.faults()).isEqualTo(2);
        assertThat(report.matched()).isZero();
        assertThat(report.rows()).extracting(EvaluationReport.Row::scenarioId, EvaluationReport.Row::matchStatus)
                .containsExactly(tuple("one", MatchStatus.AMBIGUOUS), tuple("two", MatchStatus.AMBIGUOUS));
    }

    @Test
    void averagesOnlySelectedMatchedResultsWithNonnegativeCompletedTime() {
        var labels = List.of(
                fault("s", "valid", "type", "database", START, Duration.ofSeconds(60)),
                fault("s", "zero", "type", "database", START.plusSeconds(300), Duration.ofSeconds(60)),
                fault("s", "legacy", "type", "database", START.plusSeconds(600), Duration.ofSeconds(60)),
                fault("s", "negative", "type", "database", START.plusSeconds(900), Duration.ofSeconds(60)),
                fault("s", "missing", "type", "database", START.plusSeconds(1200), Duration.ofSeconds(60)));
        Incident selected = completedIncident(1, START.plusSeconds(10), START.plusSeconds(30).plusNanos(1));
        Incident later = completedIncident(2, START.plusSeconds(20), START.plusSeconds(100));
        Incident zero = completedIncident(3, START.plusSeconds(300), START.plusSeconds(300));
        Incident legacy = analyzedIncident(4, START.plusSeconds(610), "database");
        Incident negative = completedIncident(5, START.plusSeconds(910), START.plusSeconds(905));
        EvaluationReport report = evaluator.evaluate(labels, List.of(later, negative, legacy, zero, selected));

        assertThat(report.matched()).isEqualTo(4);
        assertThat(report.timeToAnswerObservations()).isEqualTo(2);
        assertThat(report.meanTimeToAnswer()).isEqualTo(Duration.ofSeconds(15));
        assertThat(report.rows()).extracting(EvaluationReport.Row::timeToAnswer)
                .containsExactly(Duration.ofSeconds(30).plusNanos(1), Duration.ZERO, null, null, null);
        assertThat(report.rows().get(0).incidentId()).isEqualTo(selected.id().toString());
        EvaluationReport windowed = evaluator.evaluate(labels, List.of(later, negative, legacy, zero, selected),
                new TimeWindow(START, START.plusSeconds(1800)));
        assertThat(windowed.timeToAnswerObservations()).isEqualTo(2);
        assertThat(windowed.meanTimeToAnswer()).isEqualTo(report.meanTimeToAnswer());
    }

    @Test
    void neverUsesSharedOrUnselectedCompletedResults() {
        Incident shared = completedIncident(1, START.plusSeconds(15), START.plusSeconds(40));
        Incident uniqueLegacy = analyzedIncident(2, START.plusSeconds(5), "database");
        Incident later = completedIncident(3, START.plusSeconds(121), START.plusSeconds(150));
        FaultLabel first = fault("s", "first", "type", "database", START, Duration.ofSeconds(60));
        FaultLabel second = fault("s", "second", "type", "database", START.plusSeconds(10), Duration.ZERO);
        EvaluationReport report = evaluator.evaluate(List.of(first, second), List.of(shared, uniqueLegacy, later));

        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.rows()).extracting(EvaluationReport.Row::matchStatus)
                .containsExactly(MatchStatus.MATCHED, MatchStatus.AMBIGUOUS);
        assertThat(report.timeToAnswerObservations()).isZero();
        assertThat(report.meanTimeToAnswer()).isNull();
    }

    @Test
    void baselineUsesFirstProblemOfSelectedAnalysisNotExpectedOrLaterIncident() {
        FaultLabel label = fault("s", "f", "type", "database", START, Duration.ofSeconds(60));
        Incident first = withProblems(analyzedIncident(1, START.plusSeconds(10), "database"),
                problem(1, START.plusSeconds(10), "gateway"), problem(2, START.plusSeconds(20), "database"));
        Incident later = analyzedIncident(3, START.plusSeconds(30), "database");
        var report = evaluator.evaluate(List.of(label), List.of(later, first));
        var row = report.rows().get(0);
        assertThat(row.incidentId()).isEqualTo(first.id().toString());
        assertThat(row.baselineSource()).isEqualTo("gateway");
        assertThat(row.baselineCorrect()).isFalse();
        assertThat(report.baselineObservations()).isEqualTo(1);
        assertThat(report.baselineCorrectCount()).isZero();
        assertThat(report.pairedAdvisorCorrectCount()).isEqualTo(1);

        var differentExpected = fault("s", "f", "type", "gateway", START, Duration.ofSeconds(60));
        var changed = evaluator.evaluate(List.of(differentExpected), List.of(later, first)).rows().get(0);
        assertThat(changed.incidentId()).isEqualTo(row.incidentId());
        assertThat(changed.baselineSource()).isEqualTo(row.baselineSource());
        assertThat(changed.baselineCorrect()).isTrue();
    }

    @Test
    void tiedFirstProblemsRequireOneKnownTechnicalHostAcrossAllProblems() {
        FaultLabel label = fault("s", "f", "type", "db", START, Duration.ofSeconds(60));
        Instant firstTime = START.plusSeconds(10);
        Incident analyzed = analyzedIncident(1, firstTime, "other");
        Incident same = withProblems(analyzed, problem(1, firstTime, "db"), problem(2, firstTime, "db"),
                problem(3, firstTime.plusSeconds(1), "other"));
        assertThat(evaluator.evaluate(List.of(label), List.of(same)).rows().get(0).baselineSource()).isEqualTo("db");
        Incident different = withProblems(analyzed, problem(1, firstTime, "db"), problem(2, firstTime, "other"));
        Incident missing = withProblems(analyzed, problem(1, firstTime, "db"), problem(2, firstTime));
        Incident multiHost = withProblems(analyzed, problem(1, firstTime, "db", "other"));
        Incident blank = withProblems(analyzed, problem(1, firstTime, "db", " "));
        Incident repeatedHost = withProblems(analyzed, problem(1, firstTime, "db", "db"));
        assertThat(evaluator.evaluate(List.of(label), List.of(repeatedHost)).rows().get(0).baselineCorrect()).isTrue();
        Incident missingName = withProblems(analyzed, new Problem(1, "problem", 3,
                List.of(new HostRef(null, "visible")), List.of(), firstTime, null));
        for (Incident unknown : List.of(different, missing, multiHost, blank, missingName)) {
            var report = evaluator.evaluate(List.of(label), List.of(unknown));
            assertThat(report.rows().get(0).baselineSource()).isNull();
            assertThat(report.rows().get(0).baselineCorrect()).isNull();
            assertThat(report.baselineObservations()).isZero();
        }
    }

    @Test
    void pairedSummaryRecomputesFilteredRowsAndExcludesUnavailableBaseline() {
        List<FaultLabel> labels = List.of(
                fault("one", "a", "type", "db", START, Duration.ofSeconds(30)),
                fault("two", "b", "type", "db", START.plusSeconds(300), Duration.ofSeconds(30)),
                fault("two", "c", "type", "db", START.plusSeconds(600), Duration.ofSeconds(30)),
                fault("two", "d", "type", null, START.plusSeconds(900), Duration.ofSeconds(30)));
        Incident baselineRight = withProblems(analyzedIncident(1, START.plusSeconds(10), "wrong"),
                problem(1, START.plusSeconds(10), "db"));
        Incident advisorRight = withProblems(analyzedIncident(2, START.plusSeconds(310), "db"),
                problem(2, START.plusSeconds(310), "wrong"));
        Incident noBaseline = withProblems(analyzedIncident(3, START.plusSeconds(610), "db"),
                problem(3, START.plusSeconds(610), "db", "wrong"));
        Incident noExpected = analyzedIncident(4, START.plusSeconds(910), "db");
        var report = evaluator.evaluate(labels, List.of(noExpected, noBaseline, advisorRight, baselineRight));
        assertThat(report.sourceCorrect()).isEqualTo(2);
        assertThat(report.baselineObservations()).isEqualTo(2);
        assertThat(report.baselineCorrectCount()).isEqualTo(1);
        assertThat(report.pairedAdvisorCorrectCount()).isEqualTo(1);
        assertThat(report.rows().get(3).baselineSource()).isEqualTo("db");
        assertThat(report.rows().get(3).baselineCorrect()).isNull();

        var filtered = EvaluationReport.summarize(report.rows().stream()
                .filter(row -> row.scenarioId().equals("two")).toList(), null);
        assertThat(filtered.baselineObservations()).isEqualTo(1);
        assertThat(filtered.baselineCorrectCount()).isZero();
        assertThat(filtered.pairedAdvisorCorrectCount()).isEqualTo(1);
        assertThat(filtered.sourceCorrect()).isEqualTo(2);
        var empty = EvaluationReport.summarize(List.of(), null);
        assertThat(empty.baselineObservations()).isZero();
        assertThat(empty.baselineCorrectCount()).isZero();
        assertThat(empty.pairedAdvisorCorrectCount()).isZero();
    }

    @Test
    void baselineAndAdvisorCanAgreeOnBothRightAndWrongAnswers() {
        FaultLabel first = fault("s", "a", "type", "db", START, Duration.ofSeconds(30));
        FaultLabel second = fault("s", "b", "type", "db", START.plusSeconds(300), Duration.ofSeconds(30));
        var report = evaluator.evaluate(List.of(first, second), List.of(
                analyzedIncident(1, START.plusSeconds(10), "db"),
                analyzedIncident(2, START.plusSeconds(310), "wrong")));
        assertThat(report.rows()).extracting(EvaluationReport.Row::baselineCorrect).containsExactly(true, false);
        assertThat(report.baselineObservations()).isEqualTo(2);
        assertThat(report.baselineCorrectCount()).isEqualTo(1);
        assertThat(report.pairedAdvisorCorrectCount()).isEqualTo(1);
    }

    @Test
    void baselineIsUnavailableWithoutSelectedAnalysisIncludingAmbiguousCandidates() {
        Incident pending = incident(1, "db", "pending", START.plusSeconds(15));
        FaultLabel label = fault("s", "f", "type", "db", START, Duration.ofSeconds(60));
        for (var report : List.of(evaluator.evaluate(List.of(), List.of()),
                evaluator.evaluate(List.of(label), List.of()), evaluator.evaluate(List.of(label), List.of(pending)),
                evaluator.evaluate(overlappingFaults(), List.of(analyzedIncident(2, START.plusSeconds(15), "db"))))) {
            assertThat(report.baselineObservations()).isZero();
            assertThat(report.rows()).allSatisfy(row -> {
                assertThat(row.baselineSource()).isNull();
                assertThat(row.baselineCorrect()).isNull();
            });
        }
    }

    private static Problem problem(long id, Instant time, String... hosts) {
        return new Problem(id, "problem", 3, Arrays.stream(hosts)
                .map(host -> new HostRef(host, "visible " + host)).toList(), List.of(), time, null);
    }

    private static Incident withProblems(Incident incident, Problem... problems) {
        return incident.withProblems(List.of(problems));
    }

    private static Incident completedIncident(long eventId, Instant startedAt, Instant completedAt) {
        Incident legacy = analyzedIncident(eventId, startedAt, "database");
        AnalysisResult old = legacy.analysis();
        AnalysisResult result = new AnalysisResult(old.analyzedAt(), old.scope(), old.suspects(), old.deviations(),
                old.logEvidence(), old.explanation(), completedAt);
        return new Incident(legacy.id(), legacy.problemEventId(), legacy.triggerName(), legacy.severity(),
                legacy.hosts(), legacy.tags(), startedAt, null, legacy.status(), AnalysisStatus.DONE, result, null);
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
