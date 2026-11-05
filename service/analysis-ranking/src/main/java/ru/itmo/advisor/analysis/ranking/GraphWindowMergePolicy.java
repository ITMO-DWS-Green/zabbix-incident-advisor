package ru.itmo.advisor.analysis.ranking;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.Problem;
import ru.itmo.advisor.domain.model.ServiceGraph;
import ru.itmo.advisor.domain.port.IncidentMergePolicy;

/**
 * Проблема присоединяется к открытому инциденту в двух случаях.
 * <ol>
 *   <li><b>Каскад.</b> Началась не дальше {@code window} от начала инцидента (в любую сторону: события приходят
 *       не по порядку), и её хост совпадает с хостом инцидента или связан с ним цепочкой зависимостей
 *       по карте связей — в любую сторону: сбой зависимости поднимается к зависимым.</li>
 *   <li><b>Поздняя причина.</b> Началась позже окна, но не дальше {@code 2 × window}, на сервисе, от которого
 *       зависят хосты ещё открытых проблем инцидента. Так бывает, когда источник упал:
 *       зависимые сразу ловят 5xx, а у него самого срабатывает только «нет данных» — через минуту.</li>
 * </ol>
 * Подходят несколько — берётся самый ранний. Окно отсчитывается от начала инцидента, а не от последней
 * проблемы: иначе каскад «съест» и независимый сбой, начавшийся позже. Поздний сбой на <i>зависимом</i>
 * сервисе (gateway после database в {@code two-incidents}) — отдельный инцидент, даже если у него уже
 * есть каскадная проблема этого инцидента. Окно {@code 0} выключает
 * объединение.
 *
 * <p>Сервисы, которые просто зависят от одного и того же (соседи по общей зависимости), сами по себе
 * не связаны: их объединяет проблема на общей зависимости, если она есть.
 */
public class GraphWindowMergePolicy implements IncidentMergePolicy {

    private final Duration window;

    public GraphWindowMergePolicy(Duration window) {
        this.window = window;
    }

    @Override
    public Optional<Incident> mergeInto(Problem problem, List<Incident> open, ServiceGraph graph) {
        if (window.isZero() || window.isNegative()) {
            return Optional.empty();
        }
        return open.stream()
                .filter(i -> cascade(problem, i, graph) || lateCause(problem, i, graph))
                .min(Comparator.comparing(Incident::startedAt));
    }

    private boolean cascade(Problem problem, Incident incident, ServiceGraph graph) {
        if (Duration.between(incident.startedAt(), problem.startedAt()).abs().compareTo(window) > 0) {
            return false;
        }
        for (String a : problem.hostNames()) {
            for (String b : incident.hostNames()) {
                if (a.equals(b) || dependsOn(graph, a, b) || dependsOn(graph, b, a)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean lateCause(Problem problem, Incident incident, ServiceGraph graph) {
        Duration since = Duration.between(incident.startedAt(), problem.startedAt());
        if (since.compareTo(window) <= 0 || since.compareTo(window.multipliedBy(2)) > 0) {
            return false;
        }
        for (Problem symptom : incident.problems()) {
            if (!symptom.isOpen()) {
                continue;
            }
            for (String affected : symptom.hostNames()) {
                for (String cause : problem.hostNames()) {
                    if (dependsOn(graph, affected, cause)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** {@code from} зависит от {@code to} напрямую или через других. */
    private static boolean dependsOn(ServiceGraph graph, String from, String to) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(graph.dependencies(from));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (next.equals(to)) {
                return true;
            }
            if (seen.add(next)) {
                queue.addAll(graph.dependencies(next));
            }
        }
        return false;
    }
}
