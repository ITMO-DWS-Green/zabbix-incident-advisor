package ru.itmo.advisor.domain.port;

import java.util.List;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.LogEvidence;
import ru.itmo.advisor.domain.model.MetricDeviation;
import ru.itmo.advisor.domain.model.Suspect;

/** Идея 1. Ранжирует сервисы: кто вероятнее всего первопричина. Модуль analysis-ranking. */
public interface SuspectRanker {

    /** Список по убыванию вероятности. Пустой — если причину не нашли. */
    List<Suspect> rank(IncidentContext context, List<MetricDeviation> deviations, List<LogEvidence> logs);
}
