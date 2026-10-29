package ru.itmo.advisor.domain.port;

import java.util.Map;

/** Что бизнес-логика может записать в Zabbix. Реализует app через zabbix-client. */
public interface ZabbixWriter {

    /** Значение в элемент-траппер: {@code host} + {@code key}. */
    void push(String host, String key, Object value);

    /** Комментарий к проблеме. */
    void comment(long eventId, String message);

    /** Пометить проблему симптомом. */
    void markSymptom(long eventId, long causeEventId);

    /** Создать триггер; возвращает triggerid. */
    String createTrigger(String description, String expression, int priority, String comments);

    void updateTrigger(String triggerId, Map<String, Object> fields);
}
