package ru.itmo.advisor.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.domain.port.LlmClient;

class MaskingLlmClientTest {

    @Test
    void sensitiveDataNeverReachesModelAndComesBackInAnswer() {
        List<String> sentToModel = new ArrayList<>();
        List<AuditSink.Entry> audit = new ArrayList<>();
        LlmClient model = new LlmClient() {
            public Response complete(Request r) {
                String content = r.messages().get(1).content();
                sentToModel.add(content);
                // модель отвечает, используя псевдоним
                return new Response("Причина — HOST_1", "fake", 1);
            }

            public boolean enabled() {
                return true;
            }
        };
        LlmClient client = new MaskingLlmClient(new AuditingLlmClient(model, audit::add));

        var answer = client.complete(LlmClient.Request.of("explain", "system",
                "database (10.0.0.5) отвечает медленно, см. http://grafana.local/d/1, пишите admin@corp.ru",
                Set.of("database")));

        assertThat(sentToModel.get(0))
                .doesNotContain("database", "10.0.0.5", "grafana.local", "admin@corp.ru")
                .contains("HOST_1", "IP_", "URL_", "EMAIL_");
        assertThat(answer.text()).isEqualTo("Причина — database");
        assertThat(audit).singleElement().satisfies(e -> assertThat(e.request()).doesNotContain("10.0.0.5"));
    }
}
