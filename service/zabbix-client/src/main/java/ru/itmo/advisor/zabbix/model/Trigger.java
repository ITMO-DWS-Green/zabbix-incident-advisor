package ru.itmo.advisor.zabbix.model;

import java.util.List;

/** Ответ trigger.get с expandExpression, selectTags и selectHosts. */
public record Trigger(String triggerid, String description, String expression, int priority,
                      int value, int status, List<Tag> tags, List<HostRef> hosts) {

    public Trigger {
        tags = tags == null ? List.of() : tags;
        hosts = hosts == null ? List.of() : hosts;
    }
}
