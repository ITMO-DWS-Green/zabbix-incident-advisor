package ru.itmo.advisor.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Событие из коннектора Zabbix (data_type = 1).
 * Проблема: {@code value = 1}, есть name, severity, hosts, tags.
 * Восстановление: {@code value = 0} и {@code p_eventid} — id проблемы, которую оно закрывает.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ZabbixEvent(
        long clock,
        long ns,
        int value,
        long eventid,
        @JsonProperty("p_eventid") Long problemEventId,
        String name,
        Integer severity,
        List<HostRef> hosts,
        List<String> groups,
        List<Tag> tags) {

    public ZabbixEvent {
        hosts = hosts == null ? List.of() : hosts;
        groups = groups == null ? List.of() : groups;
        tags = tags == null ? List.of() : tags;
    }

    public boolean isProblem() {
        return value == 1;
    }

    public Instant time() {
        return Instant.ofEpochSecond(clock, ns);
    }
}
