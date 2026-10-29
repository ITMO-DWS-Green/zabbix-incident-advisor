package ru.itmo.advisor.llm;

import java.util.List;
import ru.itmo.advisor.domain.port.LlmClient;

/** Заглушка: модели нет, возвращает последнее сообщение пользователя. Работает без сети и ключей. */
public class StubLlmClient implements LlmClient {

    @Override
    public Response complete(Request request) {
        List<Message> messages = request.messages();
        String last = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        return new Response(last, "stub", 0);
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
