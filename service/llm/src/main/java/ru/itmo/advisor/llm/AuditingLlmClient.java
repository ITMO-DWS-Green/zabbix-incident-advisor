package ru.itmo.advisor.llm;

import java.time.Instant;
import java.util.stream.Collectors;
import ru.itmo.advisor.domain.port.LlmClient;

/** Пишет в журнал каждый вызов: что ушло в модель и что вернулось. Ставится внутри маскирования. */
public class AuditingLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final AuditSink sink;

    public AuditingLlmClient(LlmClient delegate, AuditSink sink) {
        this.delegate = delegate;
        this.sink = sink;
    }

    @Override
    public Response complete(Request request) {
        String sent = request.messages().stream().map(m -> m.role() + ": " + m.content())
                .collect(Collectors.joining("\n\n"));
        try {
            Response response = delegate.complete(request);
            sink.record(new AuditSink.Entry(Instant.now(), request.purpose(), response.model(), sent,
                    response.text(), response.durationMs(), null));
            return response;
        } catch (RuntimeException e) {
            sink.record(new AuditSink.Entry(Instant.now(), request.purpose(), null, sent, null, 0, e.getMessage()));
            throw e;
        }
    }

    @Override
    public boolean enabled() {
        return delegate.enabled();
    }
}
