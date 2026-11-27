package ru.itmo.advisor.domain.model;

import java.time.Duration;
import java.util.List;

/**
 * Временные совпадения с разметкой, не доказательство причинной связи.
 * Сводные показатели считаются по первому по времени и id однозначному разобранному
 * кандидату каждого сбоя, независимо от эталонного источника; общие кандидаты не засчитываются.
 *
 * @param faults количество всех сбоев, включая сбои без данных и неоднозначные
 * @param matched сбои хотя бы с одним однозначно сопоставленным разобранным инцидентом
 * @param sourceCorrect сбои с источником на первом месте выбранного разбора
 * @param sourceInTop3 сбои с источником в первой тройке выбранного разбора
 * @param meanTimeToAnswer среднее время от начала сбоя до completedAt выбранного разбора;
 *                         null, если достоверных неотрицательных наблюдений нет
 * @param timeToAnswerObservations количество наблюдений, участвующих в среднем
 * @param outsideWindowIncidentIds инциденты вне всех окон переданных сбоев в заданном окне наблюдения;
 *                                 null, если окно наблюдения не задано. Это не доказанные ложные тревоги;
 *                                 для полноты список сбоев должен включать все сценарии окна
 * @param baselineObservations число парных наблюдений с известным baseline и эталоном
 * @param baselineCorrectCount правильные ответы baseline на этих наблюдениях
 * @param pairedAdvisorCorrectCount правильные ответы advisor на тех же наблюдениях
 */
public record EvaluationReport(int faults, int matched, int sourceCorrect, int sourceInTop3,
                               Duration meanTimeToAnswer, List<Row> rows,
                               List<String> outsideWindowIncidentIds, int timeToAnswerObservations,
                               int baselineObservations, int baselineCorrectCount, int pairedAdvisorCorrectCount) {

    public EvaluationReport {
        rows = rows == null ? List.of() : List.copyOf(rows);
        outsideWindowIncidentIds = outsideWindowIncidentIds == null ? null : List.copyOf(outsideWindowIncidentIds);
    }

    /** Сохраняет исходный конструктор и API без окна наблюдения. */
    public EvaluationReport(int faults, int matched, int sourceCorrect, int sourceInTop3,
                            Duration meanTimeToAnswer, List<Row> rows) {
        this(faults, matched, sourceCorrect, sourceInTop3, meanTimeToAnswer, rows, null, 0);
    }

    public EvaluationReport(int faults, int matched, int sourceCorrect, int sourceInTop3,
                            Duration meanTimeToAnswer, List<Row> rows, List<String> outsideWindowIncidentIds) {
        this(faults, matched, sourceCorrect, sourceInTop3, meanTimeToAnswer, rows, outsideWindowIncidentIds, 0);
    }

    public EvaluationReport(int faults, int matched, int sourceCorrect, int sourceInTop3,
                            Duration meanTimeToAnswer, List<Row> rows, List<String> outsideWindowIncidentIds,
                            int timeToAnswerObservations) {
        this(faults, matched, sourceCorrect, sourceInTop3, meanTimeToAnswer, rows, outsideWindowIncidentIds,
                timeToAnswerObservations, 0, 0, 0);
    }

    /** Сводка по уже выбранным строкам; неоднозначность определяется до фильтрации. */
    public static EvaluationReport summarize(List<Row> rows, List<String> outsideWindowIncidentIds) {
        int matched = 0;
        int correct = 0;
        int top3 = 0;
        int observations = 0;
        int baselineObservations = 0;
        int baselineCorrect = 0;
        int pairedAdvisorCorrect = 0;
        Duration total = Duration.ZERO;
        for (Row row : rows) {
            if (row.matchStatus() != MatchStatus.MATCHED) {
                continue;
            }
            matched++;
            if (row.baselineCorrect() != null) {
                baselineObservations++;
                if (row.baselineCorrect()) {
                    baselineCorrect++;
                }
                if (Integer.valueOf(1).equals(row.rankOfExpected())) {
                    pairedAdvisorCorrect++;
                }
            }
            if (Integer.valueOf(1).equals(row.rankOfExpected())) {
                correct++;
            }
            if (row.rankOfExpected() != null && row.rankOfExpected() <= 3) {
                top3++;
            }
            if (row.timeToAnswer() != null && !row.timeToAnswer().isNegative()) {
                total = total.plus(row.timeToAnswer());
                observations++;
            }
        }
        return new EvaluationReport(rows.size(), matched, correct, top3,
                observations == 0 ? null : total.dividedBy(observations), rows, outsideWindowIncidentIds, observations,
                baselineObservations, baselineCorrect, pairedAdvisorCorrect);
    }

    public enum MatchStatus { NO_INCIDENT, NO_ANALYSIS, MATCHED, AMBIGUOUS }

    /**
     * incidentId и прогноз — первый однозначный разобранный кандидат по времени и id, только для MATCHED;
     * его выбор не зависит от эталона и определяет сводные top1/top3. candidates содержит все совпадения,
     * включая неразобранные и общие для нескольких сбоев, отмеченные как неоднозначные.
     * При отсутствии однозначного разбора и наличии общего кандидата статус — AMBIGUOUS.
     * timeToAnswer задано только для выбранного разбора с достоверным неотрицательным интервалом.
     * baselineSource — технический хост первой проблемы того же выбранного инцидента, если он однозначен;
     * baselineCorrect равен null, если baseline или эталон неизвестен. Это парное сравнение на выбранных
     * разобранных инцидентах, а не независимая оценка обнаружения baseline.
     */
    public record Row(String faultId, String type, String expectedSource, String predictedSource,
                      Integer rankOfExpected, String incidentId, MatchStatus matchStatus,
                      List<Candidate> candidates, String scenarioId, Duration timeToAnswer,
                      String baselineSource, Boolean baselineCorrect) {
        public Row {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public Row(String faultId, String type, String expectedSource, String predictedSource,
                   Integer rankOfExpected, String incidentId, MatchStatus matchStatus,
                   List<Candidate> candidates, String scenarioId, Duration timeToAnswer) {
            this(faultId, type, expectedSource, predictedSource, rankOfExpected, incidentId, matchStatus,
                    candidates, scenarioId, timeToAnswer, null, null);
        }

        public Row(String faultId, String type, String expectedSource, String predictedSource,
                   Integer rankOfExpected, String incidentId) {
            this(faultId, type, expectedSource, predictedSource, rankOfExpected, incidentId,
                    incidentId == null ? MatchStatus.NO_INCIDENT : MatchStatus.MATCHED, List.of(), null, null);
        }

        public Row(String faultId, String type, String expectedSource, String predictedSource,
                   Integer rankOfExpected, String incidentId, MatchStatus matchStatus,
                   List<Candidate> candidates, String scenarioId) {
            this(faultId, type, expectedSource, predictedSource, rankOfExpected, incidentId, matchStatus,
                    candidates, scenarioId, null);
        }
    }

    /** rankOfExpected равен null при отсутствии разбора или источника в списке. */
    public record Candidate(String incidentId, boolean ambiguous, boolean analyzed,
                            String predictedSource, Integer rankOfExpected) {
    }
}
