package ru.itmo.advisor.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Значение метрики из коннектора Zabbix (data_type = 0).
 * {@code type}: 0 — дробное, 1 — строка, 2 — лог, 3 — целое, 4 — текст.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemValue(
        HostRef host,
        List<String> groups,
        @JsonProperty("item_tags") List<Tag> itemTags,
        long itemid,
        String name,
        long clock,
        long ns,
        Object value,
        int type) {

    public ItemValue {
        groups = groups == null ? List.of() : groups;
        itemTags = itemTags == null ? List.of() : itemTags;
    }

    public boolean numeric() {
        return type == 0 || type == 3;
    }

    public double asDouble() {
        return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
    }

    public Instant time() {
        return Instant.ofEpochSecond(clock, ns);
    }
}
