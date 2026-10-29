package ru.itmo.advisor.llm;

import ru.itmo.advisor.domain.port.LlmClient;

/** Маскирует запрос перед отправкой и восстанавливает исходные значения в ответе. */
public class MaskingLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final Masker masker = new Masker();

    public MaskingLlmClient(LlmClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public Response complete(Request request) {
        Masker.Session session = masker.session(request.sensitive());
        Request masked = new Request(request.purpose(),
                request.messages().stream().map(m -> new Message(m.role(), session.mask(m.content()))).toList(),
                request.sensitive(), request.temperature());
        Response response = delegate.complete(masked);
        return new Response(session.unmask(response.text()), response.model(), response.durationMs());
    }

    @Override
    public boolean enabled() {
        return delegate.enabled();
    }
}
