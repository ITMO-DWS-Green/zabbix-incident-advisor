package ru.itmo.advisor.app.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.itmo.advisor.app.config.AdvisorProperties;
import ru.itmo.advisor.domain.port.LlmClient;
import ru.itmo.advisor.zabbix.ZabbixApi;

/** Состояние сервиса для шапки интерфейса: видна ли Zabbix, какая LLM. */
@RestController
public class InfoController {

    private final ZabbixApi zabbix;
    private final LlmClient llm;
    private final AdvisorProperties props;

    public InfoController(ZabbixApi zabbix, LlmClient llm, AdvisorProperties props) {
        this.zabbix = zabbix;
        this.llm = llm;
        this.props = props;
    }

    @GetMapping("/api/info")
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            info.put("zabbixVersion", zabbix.version());
        } catch (RuntimeException e) {
            info.put("zabbixError", e.getMessage());
        }
        info.put("llmEnabled", llm.enabled());
        info.put("llmProvider", props.llm().provider());
        info.put("llmModel", llm.enabled() ? props.llm().model() : null);
        info.put("analysisDelaySeconds", props.analysis().delay().toSeconds());
        return info;
    }
}
