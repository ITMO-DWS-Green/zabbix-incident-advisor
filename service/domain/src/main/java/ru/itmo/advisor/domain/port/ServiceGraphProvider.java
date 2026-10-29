package ru.itmo.advisor.domain.port;

import java.util.List;
import ru.itmo.advisor.domain.model.ServiceGraph;
import ru.itmo.advisor.domain.model.Tag;

/** Идея 6. Строит карту связей сервисов по тому, что известно о хостах Zabbix. Модуль analysis-graph. */
public interface ServiceGraphProvider {

    ServiceGraph build(List<HostInfo> hosts);

    /** Хост Zabbix с тегами и группами. */
    record HostInfo(String host, String name, List<Tag> tags, List<String> groups) {
    }
}
