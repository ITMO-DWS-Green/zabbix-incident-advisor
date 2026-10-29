package ru.itmo.advisor.domain.port;

import java.util.List;
import java.util.Optional;
import ru.itmo.advisor.domain.model.Incident;

/**
 * Идея 8. Решает, является ли проблема симптомом другой открытой проблемы.
 * Если да — app помечает её в Zabbix (event.acknowledge, RANK_TO_SYMPTOM). Модуль analysis-ranking.
 */
public interface CauseSymptomPolicy {

    /** @return id события проблемы-причины или пусто */
    Optional<Long> causeOf(Incident analyzed, List<Incident> openIncidents);
}
