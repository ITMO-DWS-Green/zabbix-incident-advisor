package ru.itmo.advisor.app.connector;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.itmo.advisor.app.incident.IncidentService;
import ru.itmo.advisor.domain.model.ItemValue;
import ru.itmo.advisor.domain.model.ZabbixEvent;
import ru.itmo.advisor.domain.port.ValueStreamProcessor;

/**
 * Приём от коннекторов Zabbix. Zabbix ждёт 200–204, иначе повторяет отправку.
 * Коннекторы создаёт setup: события → {@code /events}, значения элементов с тегом {@code advisor} → {@code /values}.
 */
@RestController
@RequestMapping("/zabbix/connector")
public class ConnectorController {

    private final NdjsonParser parser;
    private final IncidentService incidents;
    private final ValueStreamProcessor stream;

    public ConnectorController(NdjsonParser parser, IncidentService incidents, ValueStreamProcessor stream) {
        this.parser = parser;
        this.incidents = incidents;
        this.stream = stream;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> events(@RequestBody(required = false) String body) {
        List<ZabbixEvent> events = parser.parse(body, ZabbixEvent.class);
        incidents.onEvents(events);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/values")
    public ResponseEntity<Void> values(@RequestBody(required = false) String body) {
        stream.onValues(parser.parse(body, ItemValue.class));
        return ResponseEntity.ok().build();
    }
}
