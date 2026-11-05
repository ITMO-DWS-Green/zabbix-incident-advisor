package ru.itmo.advisor.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * Проблема Zabbix — одно срабатывание триггера. Инцидент объединяет связанные проблемы.
 *
 * @param eventId    id события проблемы в Zabbix
 * @param resolvedAt когда проблема закрылась; {@code null}, пока открыта
 */
public record Problem(
        long eventId,
        String triggerName,
        int severity,
        List<HostRef> hosts,
        List<Tag> tags,
        Instant startedAt,
        Instant resolvedAt) {

    public Problem {
        triggerName = triggerName == null ? "" : triggerName;
        hosts = hosts == null ? List.of() : List.copyOf(hosts);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** Проблема из события коннектора. */
    public static Problem of(ZabbixEvent e) {
        return new Problem(e.eventid(), e.name(), e.severity() == null ? 0 : e.severity(), e.hosts(), e.tags(),
                e.time(), null);
    }

    public boolean isOpen() {
        return resolvedAt == null;
    }

    /** Технические имена хостов проблемы. */
    public List<String> hostNames() {
        return hosts.stream().map(HostRef::host).toList();
    }
}
