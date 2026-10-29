package ru.itmo.advisor.llm;

import java.time.Duration;
import ru.itmo.advisor.domain.port.LlmClient;

/** Собирает клиент: модель → журнал → маскирование. Снаружи всегда маскирование. */
public final class LlmClients {

    private LlmClients() {
    }

    /**
     * @param provider {@code stub} или {@code openai} (любой OpenAI-совместимый сервер, в том числе Ollama)
     */
    public static LlmClient create(String provider, String baseUrl, String apiKey, String model,
                                   Duration timeout, AuditSink audit) {
        LlmClient model0 = switch (provider == null ? "stub" : provider.toLowerCase()) {
            case "openai" -> new OpenAiCompatibleLlmClient(baseUrl, apiKey, model, timeout);
            case "stub" -> new StubLlmClient();
            default -> throw new IllegalArgumentException("Неизвестный LLM-провайдер: " + provider);
        };
        return new MaskingLlmClient(new AuditingLlmClient(model0, audit));
    }
}
