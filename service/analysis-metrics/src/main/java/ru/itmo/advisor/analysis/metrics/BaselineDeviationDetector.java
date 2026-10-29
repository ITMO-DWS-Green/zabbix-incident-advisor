package ru.itmo.advisor.analysis.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.MetricDeviation;
import ru.itmo.advisor.domain.model.MetricSeries;
import ru.itmo.advisor.domain.port.MetricDeviationDetector;

/**
 * Идея 1, заглушка-образец: сравнивает значения до и после «начала инцидента минус {@code lead}»
 * по правилу трёх сигм. Нужна, чтобы конвейер работал с первого дня, — заменить своей логикой.
 *
 * <p>TODO идея 1: сезонность (время суток, день недели), устойчивые оценки (медиана, MAD),
 * точное начало отклонения, а не первая точка за порогом.
 */
public class BaselineDeviationDetector implements MetricDeviationDetector {

    private static final Duration LEAD = Duration.ofMinutes(5);
    private static final double SIGMAS = 3;

    @Override
    public List<MetricDeviation> detect(IncidentContext context) {
        Instant split = context.incident().startedAt().minus(LEAD);
        List<MetricDeviation> result = new ArrayList<>();
        for (MetricSeries series : context.metrics()) {
            List<Double> before = series.points().stream()
                    .filter(p -> p.time().isBefore(split)).map(MetricSeries.Point::value).toList();
            if (before.size() < 5) {
                continue;
            }
            double mean = before.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double std = Math.sqrt(before.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0));
            double threshold = SIGMAS * Math.max(std, Math.abs(mean) * 0.05 + 1e-9);
            series.points().stream()
                    .filter(p -> !p.time().isBefore(split))
                    .filter(p -> Math.abs(p.value() - mean) > threshold)
                    .findFirst()
                    .ifPresent(p -> result.add(new MetricDeviation(series.item().host(), series.item().itemId(),
                            series.item().name(), series.item().units(), mean, p.value(), p.time(),
                            Math.abs(p.value() - mean) / Math.max(std, 1e-9))));
        }
        result.sort(Comparator.comparing(MetricDeviation::startedAt));
        return result;
    }
}
