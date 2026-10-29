package ru.itmo.advisor.domain.port;

import java.util.List;
import java.util.Set;

/**
 * Языковая модель. Реализации в модуле llm: заглушка, OpenAI-совместимый API (OpenAI, Ollama, vLLM и т. п.).
 * Все вызовы проходят маскирование и попадают в журнал (идея 4).
 */
public interface LlmClient {

    Response complete(Request request);

    /** Включена ли настоящая модель. Если нет — {@link #complete} вернёт заглушку. */
    boolean enabled();

    /**
     * @param purpose   зачем вызов — для журнала, например {@code explain}
     * @param sensitive слова, которые нельзя отправлять наружу (имена хостов и т. п.) — будут заменены псевдонимами
     */
    record Request(String purpose, List<Message> messages, Set<String> sensitive, double temperature) {

        public Request {
            sensitive = sensitive == null ? Set.of() : Set.copyOf(sensitive);
        }

        public static Request of(String purpose, String system, String user, Set<String> sensitive) {
            return new Request(purpose, List.of(new Message("system", system), new Message("user", user)),
                    sensitive, 0.2);
        }
    }

    record Message(String role, String content) {
    }

    record Response(String text, String model, long durationMs) {
    }
}
