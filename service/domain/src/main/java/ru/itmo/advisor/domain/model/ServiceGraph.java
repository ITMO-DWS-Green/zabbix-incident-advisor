package ru.itmo.advisor.domain.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Карта связей: какой сервис (хост Zabbix) кого вызывает.
 * Ребро «A → B» значит «A зависит от B»: сбой B виден у A.
 */
public record ServiceGraph(Map<String, Set<String>> dependsOn) {

    public ServiceGraph {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        dependsOn.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
        dependsOn = Map.copyOf(copy);
    }

    public static ServiceGraph empty() {
        return new ServiceGraph(Map.of());
    }

    public Set<String> dependencies(String host) {
        return dependsOn.getOrDefault(host, Set.of());
    }

    /** Кто зависит от {@code host} — у них сбой {@code host} проявится. */
    public Set<String> dependents(String host) {
        Set<String> result = new LinkedHashSet<>();
        dependsOn.forEach((from, to) -> {
            if (to.contains(host)) {
                result.add(from);
            }
        });
        return result;
    }

    /** Все сервисы на расстоянии до {@code depth} рёбер в обе стороны, включая сам {@code host}. */
    public Set<String> neighbourhood(String host, int depth) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<Map.Entry<String, Integer>> queue = new ArrayDeque<>();
        queue.add(Map.entry(host, 0));
        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (!seen.add(current.getKey()) || current.getValue() >= depth) {
                continue;
            }
            for (String next : dependencies(current.getKey())) {
                queue.add(Map.entry(next, current.getValue() + 1));
            }
            for (String next : dependents(current.getKey())) {
                queue.add(Map.entry(next, current.getValue() + 1));
            }
        }
        return seen;
    }
}
