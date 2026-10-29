package ru.itmo.advisor.zabbix.model;

import java.util.List;

/** Ответ history.push: по записи на каждое значение — itemid или текст ошибки. */
public record PushResult(String response, List<Entry> data) {

    public record Entry(String itemid, String error) {
    }
}
