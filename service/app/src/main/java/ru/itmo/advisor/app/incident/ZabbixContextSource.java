package ru.itmo.advisor.app.incident;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ru.itmo.advisor.app.config.AdvisorProperties;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.LogSeries;
import ru.itmo.advisor.domain.model.MetricSeries;
import ru.itmo.advisor.domain.model.MonitoredItem;
import ru.itmo.advisor.domain.model.ServiceGraph;
import ru.itmo.advisor.domain.model.Tag;
import ru.itmo.advisor.domain.model.TimeWindow;
import ru.itmo.advisor.domain.port.ServiceGraphProvider;
import ru.itmo.advisor.zabbix.ZabbixApi;
import ru.itmo.advisor.zabbix.model.Host;
import ru.itmo.advisor.zabbix.model.HistoryRecord;
import ru.itmo.advisor.zabbix.model.Item;
import ru.itmo.advisor.zabbix.model.ValueType;

/**
 * Собирает данные для разбора из Zabbix:
 * сервисы в разборе — хосты проблемы и их соседи по графу; по ним — история числовых и лог-элементов.
 *
 * <p>Лог-элемент — это элемент типа «лог» или любой текстовый с тегом {@code component: logs}.
 */
@Component
public class ZabbixContextSource implements ContextSource {

    private final ZabbixApi zabbix;
    private final AdvisorProperties.Analysis cfg;

    public ZabbixContextSource(ZabbixApi zabbix, AdvisorProperties props) {
        this.zabbix = zabbix;
        this.cfg = props.analysis();
    }

    @Override
    public List<ServiceGraphProvider.HostInfo> hosts() {
        return zabbix.hosts().stream()
                .map(h -> new ServiceGraphProvider.HostInfo(h.host(), h.name(),
                        h.tags().stream().map(t -> new Tag(t.tag(), t.value())).toList(),
                        h.hostgroups().stream().map(Host.Group::name).toList()))
                .toList();
    }

    @Override
    public IncidentContext load(Incident incident, ServiceGraph graph, Instant now) {
        TimeWindow window = new TimeWindow(incident.startedAt().minus(cfg.lookback()), now);

        Set<String> scope = new LinkedHashSet<>();
        for (String host : incident.hostNames()) {
            scope.addAll(graph.neighbourhood(host, cfg.graphDepth()));
        }
        if (scope.isEmpty()) {
            return new IncidentContext(incident, window, graph, List.of(), List.of(), List.of());
        }

        List<String> hostIds = zabbix.hostsByName(scope).stream().map(Host::hostid).toList();
        List<Item> items = hostIds.isEmpty() ? List.of() : limitPerHost(zabbix.items(hostIds));

        Map<String, MonitoredItem> numeric = new LinkedHashMap<>();
        Map<String, MonitoredItem> logs = new LinkedHashMap<>();
        for (Item item : items) {
            MonitoredItem m = toMonitored(item);
            if (item.type().numeric()) {
                numeric.put(item.itemid(), m);
            } else if (item.type() == ValueType.LOG || m.tags().contains(new Tag("component", "logs"))) {
                logs.put(item.itemid(), m);
            }
        }

        List<MetricSeries> metrics = new ArrayList<>();
        for (ValueType type : List.of(ValueType.FLOAT, ValueType.UNSIGNED)) {
            List<String> ids = idsOf(numeric, items, type);
            if (ids.isEmpty()) {
                continue;
            }
            groupByItem(zabbix.history(type, ids, window.from(), window.to(), cfg.historyLimit()))
                    .forEach((itemId, records) -> metrics.add(new MetricSeries(numeric.get(itemId),
                            records.stream().map(r -> new MetricSeries.Point(time(r), r.asDouble())).toList())));
        }

        List<LogSeries> logSeries = new ArrayList<>();
        for (ValueType type : List.of(ValueType.LOG, ValueType.TEXT, ValueType.CHAR)) {
            List<String> ids = idsOf(logs, items, type);
            if (ids.isEmpty()) {
                continue;
            }
            groupByItem(zabbix.history(type, ids, window.from(), window.to(), cfg.historyLimit()))
                    .forEach((itemId, records) -> logSeries.add(new LogSeries(logs.get(itemId),
                            records.stream().map(r -> new LogSeries.Line(time(r), r.value(), r.severity(), r.source()))
                                    .toList())));
        }

        return new IncidentContext(incident, window, graph, List.copyOf(scope), metrics, logSeries);
    }

    private List<Item> limitPerHost(List<Item> items) {
        Map<String, List<Item>> byHost = items.stream()
                .collect(Collectors.groupingBy(Item::hostid, LinkedHashMap::new, Collectors.toList()));
        return byHost.values().stream().flatMap(l -> l.stream().limit(cfg.maxItemsPerHost())).toList();
    }

    private static MonitoredItem toMonitored(Item item) {
        String host = item.hosts().isEmpty() ? item.hostid() : item.hosts().get(0).host();
        return new MonitoredItem(item.itemid(), host, item.name(), item.key(), item.valueType(), item.units(),
                item.tags().stream().map(t -> new Tag(t.tag(), t.value())).toList());
    }

    private static List<String> idsOf(Map<String, MonitoredItem> selected, List<Item> items, ValueType type) {
        return items.stream().filter(i -> selected.containsKey(i.itemid()) && i.type() == type)
                .map(Item::itemid).toList();
    }

    private static Map<String, List<HistoryRecord>> groupByItem(List<HistoryRecord> records) {
        return records.stream().collect(Collectors.groupingBy(HistoryRecord::itemid, LinkedHashMap::new,
                Collectors.toList()));
    }

    private static Instant time(HistoryRecord r) {
        return Instant.ofEpochSecond(r.clock(), r.ns());
    }
}
