package ru.itmo.advisor.domain.port;

import java.util.List;
import ru.itmo.advisor.domain.model.Explanation;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.LogEvidence;
import ru.itmo.advisor.domain.model.MetricDeviation;
import ru.itmo.advisor.domain.model.Suspect;

/**
 * Идея 3. Пишет объяснение по уже найденным фактам. LLM здесь только пересказывает —
 * причину находят детекторы и ранжирование. Модуль analysis-explain.
 */
public interface ExplanationWriter {

    Explanation write(IncidentContext context, List<Suspect> suspects, List<MetricDeviation> deviations,
                      List<LogEvidence> logs);
}
