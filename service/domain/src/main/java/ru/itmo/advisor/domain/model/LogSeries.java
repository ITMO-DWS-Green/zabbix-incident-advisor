package ru.itmo.advisor.domain.model;

import java.time.Instant;
import java.util.List;

/** Записи лог-элемента за окно разбора, по возрастанию времени. */
public record LogSeries(MonitoredItem item, List<Line> lines) {

    public LogSeries {
        lines = List.copyOf(lines);
    }

    /** @param severity уровень из лог-элемента Zabbix; может быть {@code null} */
    public record Line(Instant time, String text, Integer severity, String source) {
    }
}
