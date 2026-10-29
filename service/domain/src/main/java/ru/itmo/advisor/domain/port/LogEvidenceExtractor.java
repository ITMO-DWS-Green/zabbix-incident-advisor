package ru.itmo.advisor.domain.port;

import java.util.List;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.LogEvidence;

/** Идея 2. Выбирает характерные строки логов вокруг инцидента. Модуль analysis-logs. */
public interface LogEvidenceExtractor {

    List<LogEvidence> extract(IncidentContext context);
}
