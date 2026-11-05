package ru.itmo.advisor.domain.port;

import java.util.List;
import java.util.Optional;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.Problem;
import ru.itmo.advisor.domain.model.ServiceGraph;

/**
 * Решает, к какому открытому инциденту относится новая проблема. Каскад по карте связей — один инцидент
 * и один разбор, независимые сбои — разные. Модуль analysis-ranking.
 */
public interface IncidentMergePolicy {

    /**
     * @param problem новая проблема
     * @param open    открытые инциденты
     * @param graph   карта связей
     * @return инцидент, в который добавить проблему, или пусто — тогда это новый инцидент
     */
    Optional<Incident> mergeInto(Problem problem, List<Incident> open, ServiceGraph graph);
}
