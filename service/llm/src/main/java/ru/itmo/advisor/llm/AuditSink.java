package ru.itmo.advisor.llm;

import java.time.Instant;

/** Куда писать журнал вызовов LLM. Реализует app (таблица llm_audit). */
@FunctionalInterface
public interface AuditSink {

    void record(Entry entry);

    /** @param request то, что ушло в модель, — уже замаскированное */
    record Entry(Instant at, String purpose, String model, String request, String response, long durationMs,
                 String error) {
    }
}
