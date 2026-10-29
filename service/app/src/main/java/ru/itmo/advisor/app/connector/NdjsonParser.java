package ru.itmo.advisor.app.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Построчный JSON коннектора Zabbix: одна строка — один объект. Битые строки пропускаются с предупреждением. */
@Component
public class NdjsonParser {

    private static final Logger LOG = LoggerFactory.getLogger(NdjsonParser.class);

    private final ObjectMapper json;

    public NdjsonParser(ObjectMapper json) {
        this.json = json;
    }

    public <T> List<T> parse(String body, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (body == null) {
            return result;
        }
        for (String line : body.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                result.add(json.readValue(line, type));
            } catch (Exception e) {
                LOG.warn("Строка коннектора не разобрана: {}", e.getMessage());
            }
        }
        return result;
    }
}
