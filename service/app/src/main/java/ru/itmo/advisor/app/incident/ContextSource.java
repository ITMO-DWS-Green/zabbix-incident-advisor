package ru.itmo.advisor.app.incident;

import java.time.Instant;
import java.util.List;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.ServiceGraph;
import ru.itmo.advisor.domain.port.ServiceGraphProvider;

/** Откуда конвейер берёт данные для разбора. В работе — Zabbix, в тестах — подделка. */
public interface ContextSource {

    List<ServiceGraphProvider.HostInfo> hosts();

    IncidentContext load(Incident incident, ServiceGraph graph, Instant now);
}
