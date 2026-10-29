package ru.itmo.advisor.app.storage;

import java.sql.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.itmo.advisor.llm.AuditSink;

/** Журнал вызовов LLM. Ошибка записи журнала не ломает разбор. */
@Repository
public class LlmAuditRepository {

    private static final Logger LOG = LoggerFactory.getLogger(LlmAuditRepository.class);

    private final JdbcClient jdbc;

    public LlmAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(AuditSink.Entry e) {
        try {
            jdbc.sql("INSERT INTO llm_audit (created_at, purpose, model, request, response, duration_ms, error) "
                            + "VALUES (:at, :p, :m, :rq, :rs, :d, :e)")
                    .param("at", Timestamp.from(e.at())).param("p", e.purpose()).param("m", e.model())
                    .param("rq", e.request()).param("rs", e.response()).param("d", e.durationMs())
                    .param("e", e.error())
                    .update();
        } catch (RuntimeException ex) {
            LOG.warn("Не записан журнал LLM: {}", ex.getMessage());
        }
    }
}
