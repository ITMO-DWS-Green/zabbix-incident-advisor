package ru.itmo.advisor.analysis.logs;

import java.util.List;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.LogEvidence;
import ru.itmo.advisor.domain.port.LogEvidenceExtractor;

/**
 * Идея 2, заглушка: доказательств из логов пока нет.
 *
 * <p>TODO идея 2: привести строки к шаблонам (числа, id, время → плейсхолдеры), посчитать частоту
 * шаблонов в окне и до него, выбрать новые и резко участившиеся. Строки — в {@code context.logs()}.
 */
public class NoLogEvidenceExtractor implements LogEvidenceExtractor {

    @Override
    public List<LogEvidence> extract(IncidentContext context) {
        return List.of();
    }
}
