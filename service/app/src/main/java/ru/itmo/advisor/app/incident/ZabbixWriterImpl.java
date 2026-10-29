package ru.itmo.advisor.app.incident;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ru.itmo.advisor.domain.port.ZabbixWriter;
import ru.itmo.advisor.zabbix.ZabbixApi;
import ru.itmo.advisor.zabbix.model.PushValue;

@Component
public class ZabbixWriterImpl implements ZabbixWriter {

    private final ZabbixApi zabbix;

    public ZabbixWriterImpl(ZabbixApi zabbix) {
        this.zabbix = zabbix;
    }

    @Override
    public void push(String host, String key, Object value) {
        zabbix.push(List.of(PushValue.of(host, key, value)));
    }

    @Override
    public void comment(long eventId, String message) {
        zabbix.comment(String.valueOf(eventId), message);
    }

    @Override
    public void markSymptom(long eventId, long causeEventId) {
        zabbix.markSymptom(String.valueOf(eventId), String.valueOf(causeEventId));
    }

    @Override
    public String createTrigger(String description, String expression, int priority, String comments) {
        return zabbix.createTrigger(description, expression, priority, comments);
    }

    @Override
    public void updateTrigger(String triggerId, Map<String, Object> fields) {
        zabbix.updateTrigger(triggerId, fields);
    }
}
