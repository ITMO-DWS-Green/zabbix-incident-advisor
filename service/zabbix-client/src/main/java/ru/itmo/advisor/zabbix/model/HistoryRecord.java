package ru.itmo.advisor.zabbix.model;

/**
 * Запись history.get. Для числовых типов {@code value} — число строкой.
 * Поля source, severity, logeventid, timestamp есть только у логов.
 */
public record HistoryRecord(String itemid, long clock, int ns, String value,
                            String source, Integer severity, Long logeventid, Long timestamp) {

    public double asDouble() {
        return Double.parseDouble(value);
    }
}
