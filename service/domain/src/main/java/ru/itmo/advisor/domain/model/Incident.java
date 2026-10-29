package ru.itmo.advisor.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Инцидент — проблема Zabbix, которую разбирает расширение.
 *
 * @param problemEventId id события проблемы в Zabbix
 * @param analysis       результат разбора; {@code null}, пока разбора нет
 */
public record Incident(
        UUID id,
        long problemEventId,
        String triggerName,
        int severity,
        List<HostRef> hosts,
        List<Tag> tags,
        Instant startedAt,
        Instant resolvedAt,
        IncidentStatus status,
        AnalysisStatus analysisStatus,
        AnalysisResult analysis,
        String analysisError) {

    public Incident {
        hosts = hosts == null ? List.of() : List.copyOf(hosts);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** Технические имена хостов проблемы. */
    public List<String> hostNames() {
        return hosts.stream().map(HostRef::host).toList();
    }
}
