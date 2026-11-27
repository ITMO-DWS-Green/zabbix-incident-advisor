package ru.itmo.advisor.domain.port;

import java.util.List;
import ru.itmo.advisor.domain.model.EvaluationReport;
import ru.itmo.advisor.domain.model.FaultLabel;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.TimeWindow;

/** Идея 7. Сравнивает разборы с разметкой сбоев test-server. Модуль analysis-evaluation. */
public interface QualityEvaluator {

    EvaluationReport evaluate(List<FaultLabel> labels, List<Incident> incidents);

    /**
     * Учитывает только инциденты, начавшиеся в [from, to). Окно должно быть полностью размечено;
     * labels должны включать все сценарии этого окна.
     */
    default EvaluationReport evaluate(List<FaultLabel> labels, List<Incident> incidents, TimeWindow observationWindow) {
        throw new UnsupportedOperationException("Окно наблюдения не поддерживается");
    }
}
