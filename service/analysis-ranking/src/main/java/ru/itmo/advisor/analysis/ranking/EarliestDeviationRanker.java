package ru.itmo.advisor.analysis.ranking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.LogEvidence;
import ru.itmo.advisor.domain.model.MetricDeviation;
import ru.itmo.advisor.domain.model.Suspect;
import ru.itmo.advisor.domain.port.SuspectRanker;

/**
 * Идея 1, заглушка-образец: кто отклонился раньше всех, тот и первый подозреваемый.
 * Если отклонений нет — хосты самой проблемы.
 *
 * <p>TODO идея 1: учитывать граф (сбой зависимости объясняет сбой зависимого), силу отклонения,
 * доказательства из логов; калибровать по точности на стенде (идея 7) и оценкам инженеров (идея 13).
 */
public class EarliestDeviationRanker implements SuspectRanker {

    @Override
    public List<Suspect> rank(IncidentContext context, List<MetricDeviation> deviations, List<LogEvidence> logs) {
        Map<String, List<MetricDeviation>> byHost = new LinkedHashMap<>();
        deviations.forEach(d -> byHost.computeIfAbsent(d.host(), h -> new ArrayList<>()).add(d));

        List<Suspect> suspects = new ArrayList<>();
        byHost.forEach((host, list) -> {
            MetricDeviation first = list.stream().min(Comparator.comparing(MetricDeviation::startedAt)).orElseThrow();
            List<String> reasons = list.stream().limit(3)
                    .map(d -> d.metric() + ": " + round(d.expected()) + " → " + round(d.actual()))
                    .toList();
            suspects.add(new Suspect(host, 0, first.startedAt(), reasons));
        });
        suspects.sort(Comparator.comparing(Suspect::firstDeviationAt));

        if (suspects.isEmpty()) {
            return context.incident().hostNames().stream()
                    .map(h -> new Suspect(h, 0.1, context.incident().startedAt(), List.of("хост проблемы")))
                    .toList();
        }
        List<Suspect> scored = new ArrayList<>();
        for (int i = 0; i < suspects.size(); i++) {
            Suspect s = suspects.get(i);
            scored.add(new Suspect(s.host(), 1.0 / (i + 1), s.firstDeviationAt(), s.reasons()));
        }
        return scored;
    }

    private static String round(double v) {
        return Math.abs(v) >= 100 ? String.valueOf(Math.round(v)) : String.format("%.3f", v);
    }
}
