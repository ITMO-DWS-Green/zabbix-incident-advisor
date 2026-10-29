package ru.itmo.advisor.domain.port;

import java.util.List;
import ru.itmo.advisor.domain.model.EvaluationReport;
import ru.itmo.advisor.domain.model.FaultLabel;
import ru.itmo.advisor.domain.model.Incident;

/** Идея 7. Сравнивает разборы с разметкой сбоев test-server. Модуль analysis-evaluation. */
public interface QualityEvaluator {

    EvaluationReport evaluate(List<FaultLabel> labels, List<Incident> incidents);
}
