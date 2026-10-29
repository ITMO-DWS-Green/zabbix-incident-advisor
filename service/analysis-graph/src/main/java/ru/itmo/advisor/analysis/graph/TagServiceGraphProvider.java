package ru.itmo.advisor.analysis.graph;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.itmo.advisor.domain.model.ServiceGraph;
import ru.itmo.advisor.domain.port.ServiceGraphProvider;

/**
 * Идея 6, базовая версия: связи из тега хоста {@code depends_on} — список хостов через запятую.
 * Стенд test-server заводит эти теги сам (setup.py).
 *
 * <p>TODO идея 6: связи из логов (сервис пишет, что сосед недоступен), из traceId, из шаблонов.
 */
public class TagServiceGraphProvider implements ServiceGraphProvider {

    public static final String TAG = "depends_on";

    @Override
    public ServiceGraph build(List<HostInfo> hosts) {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (HostInfo host : hosts) {
            Set<String> deps = new LinkedHashSet<>();
            host.tags().stream()
                    .filter(t -> TAG.equals(t.tag()) && t.value() != null)
                    .flatMap(t -> Arrays.stream(t.value().split(",")))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(deps::add);
            edges.put(host.host(), deps);
        }
        return new ServiceGraph(edges);
    }
}
