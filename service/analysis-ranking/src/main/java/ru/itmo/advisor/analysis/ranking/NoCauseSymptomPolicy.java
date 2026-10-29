package ru.itmo.advisor.analysis.ranking;

import java.util.List;
import java.util.Optional;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.port.CauseSymptomPolicy;

/**
 * Идея 8, заглушка: ничего не помечает симптомом.
 *
 * <p>TODO идея 8: если первопричина разбора — хост другой открытой проблемы, и та началась раньше,
 * вернуть её problemEventId. app сам пометит текущую проблему симптомом в Zabbix.
 */
public class NoCauseSymptomPolicy implements CauseSymptomPolicy {

    @Override
    public Optional<Long> causeOf(Incident analyzed, List<Incident> openIncidents) {
        return Optional.empty();
    }
}
