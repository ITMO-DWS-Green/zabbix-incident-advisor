package ru.itmo.advisor.app.config;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.itmo.advisor.app.storage.LlmAuditRepository;
import ru.itmo.advisor.domain.port.LlmClient;
import ru.itmo.advisor.llm.LlmClients;
import ru.itmo.advisor.zabbix.TokenProvider;
import ru.itmo.advisor.zabbix.ZabbixApi;

/** Инфраструктура: клиент Zabbix, LLM, часы. Бизнес-логике сюда лезть не нужно. */
@Configuration
public class InfraConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ZabbixApi zabbixApi(AdvisorProperties props) {
        var z = props.zabbix();
        TokenProvider tokens = z.token() != null && !z.token().isBlank()
                ? TokenProvider.fixed(z.token())
                : TokenProvider.file(Path.of(z.tokenFile() == null ? "/secrets/zabbix-api-token" : z.tokenFile()));
        return ZabbixApi.create(z.url(), tokens);
    }

    @Bean
    LlmClient llmClient(AdvisorProperties props, LlmAuditRepository audit) {
        var l = props.llm();
        return LlmClients.create(l.provider(), l.baseUrl(), l.apiKey(), l.model(), l.timeout(), audit::record);
    }
}
