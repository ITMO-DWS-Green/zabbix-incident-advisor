package ru.itmo.advisor.app.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.START;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.fault;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.incident;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.itmo.advisor.analysis.evaluation.TimeOverlapEvaluator;
import ru.itmo.advisor.app.storage.IncidentRepository;
import ru.itmo.advisor.app.storage.LabelRepository;
import ru.itmo.advisor.domain.port.QualityEvaluator;
import ru.itmo.advisor.domain.model.AnalysisResult;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.EvaluationReport;
import ru.itmo.advisor.domain.model.EvaluationReport.Candidate;
import ru.itmo.advisor.domain.model.EvaluationReport.MatchStatus;
import ru.itmo.advisor.domain.model.EvaluationReport.Row;
import ru.itmo.advisor.domain.model.FaultLabel;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.Suspect;

class LiveEvaluationServiceTest {
    private final JdbcClient jdbc = mock(JdbcClient.class, Mockito.RETURNS_DEEP_STUBS);
    private final LabelRepository labels = mock(LabelRepository.class);
    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final QualityEvaluator evaluator = mock(QualityEvaluator.class);
    private final LiveEvaluationService service = new LiveEvaluationService(jdbc, labels, incidents, evaluator);

    @Test
    void countAndByteLimitsRejectBeforeMaterialization() {
        when(jdbc.sql(anyString()).param(org.mockito.ArgumentMatchers.eq("sentinel"), any())
                .query(Long.class).single()).thenReturn(1001L, 0L, 0L);
        assertThatThrownBy(() -> service.evaluate(null)).isInstanceOf(LiveEvaluationService.LimitExceeded.class);
        verify(labels, never()).find(any());
        verify(incidents, never()).findAll();
        verify(evaluator, never()).evaluate(any(), any());
    }

    @Test
    void oversizedAggregateRejectsBeforeMaterialization() {
        when(jdbc.sql(anyString()).param(org.mockito.ArgumentMatchers.eq("sentinel"), any())
                .query(Long.class).single()).thenReturn(1L);
        when(jdbc.sql(anyString()).query(BigInteger.class).single())
                .thenReturn(BigInteger.valueOf(LiveEvaluationService.MAX_BYTES + 1));
        assertThatThrownBy(() -> service.evaluate(null)).isInstanceOf(LiveEvaluationService.LimitExceeded.class);
        verify(labels, never()).find(any());
        verify(incidents, never()).findAll();
    }

    @Test
    void responseBoundAccountsForRepeatedPredictionsAndNeverOverflows() {
        BigInteger huge = LiveEvaluationService.responseBound(Long.MAX_VALUE, Long.MAX_VALUE,
                BigInteger.valueOf(Long.MAX_VALUE), BigInteger.valueOf(Long.MAX_VALUE));
        assertThat(huge).isGreaterThan(BigInteger.valueOf(Long.MAX_VALUE));
        assertThat(LiveEvaluationService.responseBound(1000, 1, BigInteger.valueOf(1024),
                BigInteger.valueOf(1024)))
                .isGreaterThan(BigInteger.valueOf(6_000_000));
        assertThatThrownBy(() -> LiveEvaluationService.responseBound(-1, 0, BigInteger.ZERO, BigInteger.ZERO))
                .isInstanceOf(LiveEvaluationService.LimitExceeded.class);
    }

    @Test
    void rejectsMalformedAnalysisBeforeMaterialization() {
        when(jdbc.sql(anyString()).param(org.mockito.ArgumentMatchers.eq("sentinel"), any())
                .query(Long.class).single()).thenReturn(1L);
        when(jdbc.sql(anyString()).query(BigInteger.class).single()).thenReturn(BigInteger.ZERO);
        when(jdbc.sql(anyString()).query(Long.class).single()).thenReturn(1L);
        assertThatThrownBy(() -> service.evaluate(null)).isInstanceOf(LiveEvaluationService.InvalidAnalysis.class);
        verify(labels, never()).find(any());
        verify(incidents, never()).findAll();
        verify(evaluator, never()).evaluate(any(), any());
    }

    @Test
    void rejectsSuspectWorkAndResponseAmplificationBeforeRepositories() {
        when(jdbc.sql(anyString()).param(org.mockito.ArgumentMatchers.eq("sentinel"), any())
                .query(Long.class).single()).thenReturn(1000L, 1L, 0L);
        when(jdbc.sql(anyString()).query(Long.class).single()).thenReturn(0L);
        when(jdbc.sql(anyString()).param(org.mockito.ArgumentMatchers.eq("max"), any())
                .query(Long.class).single()).thenReturn(0L);
        when(jdbc.sql(anyString()).query(BigInteger.class).single())
                .thenReturn(BigInteger.valueOf(10), BigInteger.valueOf(1001));
        assertThatThrownBy(() -> service.evaluate(null)).isInstanceOf(LiveEvaluationService.LimitExceeded.class);
        verify(labels, never()).find(any());
        verify(incidents, never()).findAll();
        verify(evaluator, never()).evaluate(any(), any());
    }

    @Test
    void multiplicationCheckCannotOverflow() {
        assertThat(LiveEvaluationService.exceedsProduct(100, 1000, 100_000)).isFalse();
        assertThat(LiveEvaluationService.exceedsProduct(101, 1000, 100_000)).isTrue();
        assertThat(LiveEvaluationService.exceedsProduct(Long.MAX_VALUE, Long.MAX_VALUE, 100_000)).isTrue();
        assertThat(LiveEvaluationService.exceedsProduct(Long.MAX_VALUE, 0, 100_000)).isFalse();
        assertThat(LiveEvaluationService.exceedsProduct(-1, 0, 100_000)).isTrue();
    }

    @SuppressWarnings("unchecked")
    private LiveEvaluationService serviceWithRealEvaluator(int labelCount, int incidentCount) {
        JdbcClient db = mock(JdbcClient.class, invocation -> {
            if (!invocation.getMethod().getName().equals("sql")) {
                return Mockito.RETURNS_DEFAULTS.answer(invocation);
            }
            String sql = invocation.getArgument(0);
            JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            when(spec.param(anyString(), any())).thenAnswer(call -> {
                params.put(call.getArgument(0), call.getArgument(1));
                return spec;
            });
            JdbcClient.MappedQuerySpec<BigInteger> big = mock(JdbcClient.MappedQuerySpec.class);
            when(big.single()).thenReturn(BigInteger.ZERO);
            when(spec.query(BigInteger.class)).thenReturn(big);
            JdbcClient.MappedQuerySpec<Long> counts = mock(JdbcClient.MappedQuerySpec.class);
            when(counts.single()).thenReturn(sql.contains("FROM (SELECT 1 FROM fault_label") ? (long) labelCount
                    : sql.contains("FROM (SELECT 1 FROM incident ") ? (long) incidentCount : 0L);
            when(spec.query(Long.class)).thenReturn(counts);
            JdbcClient.MappedQuerySpec<String> strings = mock(JdbcClient.MappedQuerySpec.class);
            when(strings.list()).thenAnswer(call -> {
                if (sql.contains("SELECT fault_id FROM fault_label WHERE scenario_id")) {
                    return labels.find(null).stream().filter(label -> label.scenarioId().equals(params.get("scenario")))
                            .map(FaultLabel::faultId).toList();
                }
                return labels.find(null).stream().map(FaultLabel::faultId).toList();
            });
            when(spec.query(String.class)).thenReturn(strings);
            JdbcClient.MappedQuerySpec<UUID> uuids = mock(JdbcClient.MappedQuerySpec.class);
            when(uuids.list()).thenAnswer(call -> incidents.findAll().stream().map(Incident::id).toList());
            when(spec.query(UUID.class)).thenReturn(uuids);
            return spec;
        });
        when(labels.findByIds(any())).thenAnswer(call -> labels.find(null));
        when(incidents.findByIds(any())).thenAnswer(call -> incidents.findAll());
        return new LiveEvaluationService(db, labels, incidents, new TimeOverlapEvaluator());
    }

    @Test
    void filtersOnlyAfterFindingAmbiguityAcrossScenariosAndRecountsMetrics() {
        LiveEvaluationService realService = serviceWithRealEvaluator(7, 5);
        List<FaultLabel> allLabels = List.of(
                fault("one", "shared", "type", "database", START, Duration.ofSeconds(60)),
                fault("two", "overlap", "type", "database", START.plusSeconds(10), Duration.ofSeconds(10)),
                fault("one", "top1", "type", "database", START.plusSeconds(300), Duration.ofSeconds(60)),
                fault("one", "top3", "type", "database", START.plusSeconds(600), Duration.ofSeconds(60)),
                fault("one", "top4", "type", "database", START.plusSeconds(900), Duration.ofSeconds(60)),
                fault("one", "missing", "type", "database", START.plusSeconds(1200), Duration.ofSeconds(60)),
                fault("two", "other", "type", "database", START.plusSeconds(1500), Duration.ofSeconds(60)));
        Incident shared = analyzedIncident(1, START.plusSeconds(15), "database");
        List<Incident> allIncidents = List.of(shared,
                analyzedIncident(2, START.plusSeconds(310), "database"),
                analyzedIncident(3, START.plusSeconds(610), "other", "another", "database"),
                analyzedIncident(4, START.plusSeconds(910), "other", "another", "third", "database"),
                analyzedIncident(5, START.plusSeconds(1510), "database"));
        when(labels.find(null)).thenReturn(allLabels);
        when(incidents.findAll()).thenReturn(allIncidents);

        EvaluationReport full = realService.evaluate(null);
        EvaluationReport filtered = realService.evaluate("one");
        EvaluationReport other = realService.evaluate("two");
        EvaluationReport missing = realService.evaluate("unknown");

        assertThat(full.faults()).isEqualTo(7);
        assertThat(full.matched()).isEqualTo(4);
        assertThat(full.sourceCorrect()).isEqualTo(2);
        assertThat(full.sourceInTop3()).isEqualTo(3);
        assertThat(full.meanTimeToAnswer()).isNull();
        assertThat(full.outsideWindowIncidentIds()).isNull();

        assertThat(filtered.faults()).isEqualTo(5);
        assertThat(filtered.matched()).isEqualTo(3);
        assertThat(filtered.sourceCorrect()).isEqualTo(1);
        assertThat(filtered.sourceInTop3()).isEqualTo(2);
        assertThat(filtered.meanTimeToAnswer()).isNull();
        assertThat(filtered.outsideWindowIncidentIds()).isNull();
        assertThat(filtered.rows()).containsExactlyElementsOf(full.rows().stream()
                .filter(row -> "one".equals(row.scenarioId())).toList());
        assertThat(filtered.rows()).extracting(Row::faultId, Row::matchStatus, Row::rankOfExpected)
                .containsExactly(tuple("shared", MatchStatus.AMBIGUOUS, null),
                        tuple("top1", MatchStatus.MATCHED, 1), tuple("top3", MatchStatus.MATCHED, 3),
                        tuple("top4", MatchStatus.MATCHED, 4), tuple("missing", MatchStatus.NO_INCIDENT, null));
        assertThat(filtered.rows().get(0).candidates())
                .extracting(Candidate::incidentId, Candidate::ambiguous, Candidate::analyzed)
                .containsExactly(tuple(shared.id().toString(), true, true));
        assertThat(filtered.rows().get(0).incidentId()).isNull();

        assertThat(other.faults()).isEqualTo(2);
        assertThat(other.matched()).isEqualTo(1);
        assertThat(other.sourceCorrect()).isEqualTo(1);
        assertThat(other.sourceInTop3()).isEqualTo(1);
        assertThat(other.rows()).extracting(Row::matchStatus).containsExactly(MatchStatus.AMBIGUOUS, MatchStatus.MATCHED);
        assertThat(other.meanTimeToAnswer()).isNull();
        assertThat(other.outsideWindowIncidentIds()).isNull();

        assertThat(missing.faults()).isZero();
        assertThat(missing.matched()).isZero();
        assertThat(missing.sourceCorrect()).isZero();
        assertThat(missing.sourceInTop3()).isZero();
        assertThat(missing.rows()).isEmpty();
        assertThat(missing.meanTimeToAnswer()).isNull();
        assertThat(missing.outsideWindowIncidentIds()).isNull();
        verify(labels, org.mockito.Mockito.atLeastOnce()).findByIds(any());
    }

    @Test
    void filtersTimeObservationsWithoutChangingCrossScenarioAmbiguity() {
        LiveEvaluationService realService = serviceWithRealEvaluator(5, 4);
        List<FaultLabel> allLabels = List.of(
                fault("one", "shared", "type", "database", START, Duration.ofSeconds(60)),
                fault("two", "shared-other", "type", "database", START.plusSeconds(10), Duration.ofSeconds(10)),
                fault("one", "measured", "type", "database", START.plusSeconds(300), Duration.ofSeconds(60)),
                fault("one", "legacy", "type", "database", START.plusSeconds(600), Duration.ofSeconds(60)),
                fault("two", "measured", "type", "database", START.plusSeconds(900), Duration.ofSeconds(60)));
        when(labels.find(null)).thenReturn(allLabels);
        when(incidents.findAll()).thenReturn(List.of(
                completedIncident(1, START.plusSeconds(15), START.plusSeconds(25)),
                completedIncident(2, START.plusSeconds(310), START.plusSeconds(320)),
                analyzedIncident(3, START.plusSeconds(610), "database"),
                completedIncident(4, START.plusSeconds(910), START.plusSeconds(960))));

        EvaluationReport full = realService.evaluate(null);
        EvaluationReport one = realService.evaluate("one");
        EvaluationReport two = realService.evaluate("two");
        EvaluationReport unknown = realService.evaluate("unknown");
        assertThat(full.meanTimeToAnswer()).isEqualTo(Duration.ofSeconds(40));
        assertThat(full.timeToAnswerObservations()).isEqualTo(2);
        assertThat(one.meanTimeToAnswer()).isEqualTo(Duration.ofSeconds(20));
        assertThat(one.timeToAnswerObservations()).isEqualTo(1);
        assertThat(one.matched()).isEqualTo(2);
        assertThat(one.rows().get(0).matchStatus()).isEqualTo(MatchStatus.AMBIGUOUS);
        assertThat(two.meanTimeToAnswer()).isEqualTo(Duration.ofSeconds(60));
        assertThat(two.timeToAnswerObservations()).isEqualTo(1);
        assertThat(two.rows().get(0).matchStatus()).isEqualTo(MatchStatus.AMBIGUOUS);
        assertThat(unknown.meanTimeToAnswer()).isNull();
        assertThat(unknown.timeToAnswerObservations()).isZero();
    }

    @Test
    void missingScenarioDoesNotMaterializeOrEvaluate() {
        when(jdbc.sql(anyString()).param(org.mockito.ArgumentMatchers.eq("scenario"), any())
                .param(org.mockito.ArgumentMatchers.eq("limit"), any()).query(String.class).list())
                .thenReturn(List.of());
        assertThat(service.evaluate("missing").rows()).isEmpty();
        verify(labels, never()).findByIds(any());
        verify(incidents, never()).findByIds(any());
        verify(evaluator, never()).evaluate(any(), any());
    }

    @Test
    void targetWithoutIncidentsIsNotDiscarded() {
        FaultLabel target = fault("one", "target", "type", "database", START, Duration.ofSeconds(60));
        when(jdbc.sql(anyString()).param(org.mockito.ArgumentMatchers.eq("scenario"), any())
                .param(org.mockito.ArgumentMatchers.eq("limit"), any()).query(String.class).list())
                .thenReturn(List.of("target"));
        when(jdbc.sql(anyString()).param(org.mockito.ArgumentMatchers.eq("targets"), any())
                .param(org.mockito.ArgumentMatchers.eq("limit"), any()).query(UUID.class).list())
                .thenReturn(List.of());
        when(jdbc.sql(anyString()).param(org.mockito.ArgumentMatchers.eq("ids"), any())
                .query(BigInteger.class).single()).thenReturn(BigInteger.ZERO);
        when(labels.findByIds(List.of("target"))).thenReturn(List.of(target));
        when(incidents.findByIds(List.of())).thenReturn(List.of());
        EvaluationReport report = new LiveEvaluationService(jdbc, labels, incidents, new TimeOverlapEvaluator())
                .evaluate("one");
        assertThat(report.rows()).extracting(Row::faultId, Row::matchStatus)
                .containsExactly(tuple("target", MatchStatus.NO_INCIDENT));
        verify(incidents, never()).findAll();
    }

    @Test
    void selectedLimitSentinelsRejectBeforeMaterialization() {
        when(jdbc.sql(anyString()).param(org.mockito.ArgumentMatchers.eq("scenario"), any())
                .param(org.mockito.ArgumentMatchers.eq("limit"), any()).query(String.class).list())
                .thenReturn(java.util.Collections.nCopies(LiveEvaluationService.MAX_LABELS + 1, "x"));
        assertThatThrownBy(() -> service.evaluate("one")).isInstanceOf(LiveEvaluationService.LimitExceeded.class);
        verify(labels, never()).findByIds(any());
        verify(incidents, never()).findByIds(any());
        verify(evaluator, never()).evaluate(any(), any());
    }

    @Test
    void liveOperationsUseIndependentReadOnlyRepeatableReadTransactions() throws Exception {
        for (String method : List.of("evaluate", "scenarios")) {
            Transactional tx = method.equals("evaluate")
                    ? LiveEvaluationService.class.getMethod(method, String.class).getAnnotation(Transactional.class)
                    : LiveEvaluationService.class.getMethod(method).getAnnotation(Transactional.class);
            assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
            assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
            assertThat(tx.readOnly()).isTrue();
        }
    }

    private static Incident completedIncident(long eventId, Instant startedAt, Instant completedAt) {
        Incident legacy = analyzedIncident(eventId, startedAt, "database");
        AnalysisResult old = legacy.analysis();
        AnalysisResult result = new AnalysisResult(old.analyzedAt(), old.scope(), old.suspects(), old.deviations(),
                old.logEvidence(), old.explanation(), completedAt);
        return new Incident(legacy.id(), legacy.problemEventId(), legacy.triggerName(), legacy.severity(),
                legacy.hosts(), legacy.tags(), startedAt, null, legacy.status(), AnalysisStatus.DONE, result, null);
    }

    private static Incident analyzedIncident(long eventId, Instant startedAt, String... rankedHosts) {
        Incident pending = incident(eventId, rankedHosts[0], "trigger", startedAt);
        List<Suspect> suspects = Arrays.stream(rankedHosts)
                .map(host -> new Suspect(host, 1, startedAt, List.of())).toList();
        AnalysisResult result = new AnalysisResult(startedAt.plusSeconds(30), List.of(rankedHosts), suspects,
                List.of(), List.of(), null);
        return new Incident(pending.id(), pending.problemEventId(), pending.triggerName(), pending.severity(),
                pending.hosts(), pending.tags(), startedAt, null, pending.status(), AnalysisStatus.DONE, result, null);
    }
}
