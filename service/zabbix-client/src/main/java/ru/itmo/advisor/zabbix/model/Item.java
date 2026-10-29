package ru.itmo.advisor.zabbix.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Ответ item.get с selectTags и selectHosts. */
public record Item(
        String itemid,
        String hostid,
        String name,
        @JsonProperty("key_") String key,
        @JsonProperty("value_type") int valueType,
        String units,
        List<Tag> tags,
        List<HostRef> hosts,
        String lastvalue,
        Long lastclock,
        int status,
        int state) {

    public Item {
        tags = tags == null ? List.of() : tags;
        hosts = hosts == null ? List.of() : hosts;
    }

    public ValueType type() {
        return ValueType.of(valueType);
    }
}
