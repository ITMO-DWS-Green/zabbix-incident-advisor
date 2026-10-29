package ru.itmo.advisor.domain.model;

import java.util.List;

/**
 * Элемент данных Zabbix.
 *
 * @param valueType 0 — дробное, 1 — строка, 2 — лог, 3 — целое, 4 — текст
 */
public record MonitoredItem(String itemId, String host, String name, String key, int valueType, String units,
                            List<Tag> tags) {

    public MonitoredItem {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public boolean numeric() {
        return valueType == 0 || valueType == 3;
    }

    public boolean hasTag(String tag) {
        return tags.stream().anyMatch(t -> t.tag().equals(tag));
    }
}
