package ru.itmo.advisor.app.storage;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.itmo.advisor.app.config.AdvisorProperties;
import ru.itmo.advisor.app.config.AdvisorProperties.Retention;

@Repository
public class RetentionSettingsRepository {

    private final JdbcClient jdbc;
    private final Retention defaults;

    public RetentionSettingsRepository(JdbcClient jdbc, AdvisorProperties properties) {
        this.jdbc = jdbc;
        this.defaults = properties.retention();
    }

    public Retention get() {
        return jdbc.sql("SELECT incidents, llm_audit FROM retention_settings WHERE singleton")
                .query((row, number) -> new Retention(Duration.parse(row.getString("incidents")),
                        Duration.parse(row.getString("llm_audit"))))
                .optional()
                .orElse(defaults);
    }

    public void save(Retention retention) {
        jdbc.sql("""
                INSERT INTO retention_settings (incidents, llm_audit)
                VALUES (:incidents, :llmAudit)
                ON CONFLICT (singleton) DO UPDATE
                SET incidents = EXCLUDED.incidents, llm_audit = EXCLUDED.llm_audit
                """)
                .param("incidents", retention.incidents().toString())
                .param("llmAudit", retention.llmAudit().toString())
                .update();
    }
}
