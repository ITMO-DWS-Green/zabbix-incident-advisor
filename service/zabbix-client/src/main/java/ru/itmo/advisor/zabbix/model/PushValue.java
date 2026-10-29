package ru.itmo.advisor.zabbix.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Значение для history.push. Элемент задаётся либо {@code itemid}, либо парой {@code host} + {@code key}.
 * Элемент должен быть траппером или HTTP-агентом с включённым приёмом.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PushValue(String itemid, String host, String key, String value, Long clock, Integer ns) {

    public static PushValue of(String host, String key, Object value) {
        return new PushValue(null, host, key, String.valueOf(value), null, null);
    }
}
