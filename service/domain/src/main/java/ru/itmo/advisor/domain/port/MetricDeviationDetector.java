package ru.itmo.advisor.domain.port;

import java.util.List;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.MetricDeviation;

/** Идея 1. Находит метрики, которые отклонились от обычного поведения, и когда это началось. Модуль analysis-metrics. */
public interface MetricDeviationDetector {

    List<MetricDeviation> detect(IncidentContext context);
}
