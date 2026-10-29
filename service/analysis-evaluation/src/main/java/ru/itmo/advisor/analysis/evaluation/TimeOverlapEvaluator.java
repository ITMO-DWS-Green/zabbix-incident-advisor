package ru.itmo.advisor.analysis.evaluation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import ru.itmo.advisor.domain.model.EvaluationReport;
import ru.itmo.advisor.domain.model.FaultLabel;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.Suspect;
import ru.itmo.advisor.domain.port.QualityEvaluator;

/**
 * Идея 7, базовая версия: сбой считается найденным, если есть разобранный инцидент, начавшийся
 * в окне сбоя (плюс {@code GRACE} после конца). Для сбоя берётся самый ранний такой инцидент.
 *
 * <p>TODO идея 7: учитывать все инциденты сбоя, а не первый; время до разбора по каждому;
 * отчёт по типам сбоев; ложные срабатывания вне окон.
 */
public class TimeOverlapEvaluator implements QualityEvaluator {

    private static final Duration GRACE = Duration.ofMinutes(2);

    @Override
    public EvaluationReport evaluate(List<FaultLabel> labels, List<Incident> incidents) {
        List<EvaluationReport.Row> rows = new ArrayList<>();
        int matched = 0;
        int correct = 0;
        int top3 = 0;
        long answerMillis = 0;
        int answered = 0;
        for (FaultLabel label : labels) {
            Optional<Incident> hit = incidents.stream()
                    .filter(i -> i.analysis() != null)
                    .filter(i -> !i.startedAt().isBefore(label.start())
                            && i.startedAt().isBefore(label.end().plus(GRACE)))
                    .min(Comparator.comparing(Incident::startedAt));
            if (hit.isEmpty()) {
                rows.add(new EvaluationReport.Row(label.faultId(), label.type(), label.source(), null, null, null));
                continue;
            }
            matched++;
            Incident incident = hit.get();
            List<String> ranked = incident.analysis().suspects().stream().map(Suspect::host).toList();
            int rank = ranked.indexOf(label.source());
            if (rank == 0) {
                correct++;
            }
            if (rank >= 0 && rank < 3) {
                top3++;
            }
            Instant analyzedAt = incident.analysis().analyzedAt();
            if (analyzedAt != null) {
                answerMillis += Duration.between(label.start(), analyzedAt).toMillis();
                answered++;
            }
            rows.add(new EvaluationReport.Row(label.faultId(), label.type(), label.source(),
                    incident.analysis().topSuspect(), rank >= 0 ? rank + 1 : null, incident.id().toString()));
        }
        Duration mean = answered == 0 ? null : Duration.ofMillis(answerMillis / answered);
        return new EvaluationReport(labels.size(), matched, correct, top3, mean, rows);
    }
}
