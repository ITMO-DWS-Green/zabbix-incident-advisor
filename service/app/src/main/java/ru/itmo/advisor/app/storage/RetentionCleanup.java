package ru.itmo.advisor.app.storage;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.advisor.app.config.AdvisorProperties.Retention;

@Component
public class RetentionCleanup {

    private final JdbcClient jdbc;
    private final RetentionSettingsRepository settings;
    private final Clock clock;

    public RetentionCleanup(JdbcClient jdbc, RetentionSettingsRepository settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @Transactional
    public void deleteExpired() {
        Retention retention = settings.get();
        Instant now = clock.instant();
        jdbc.sql("DELETE FROM incident WHERE status = 'RESOLVED' AND resolved_at < :cutoff")
                .param("cutoff", Timestamp.from(now.minus(retention.incidents())))
                .update();
        jdbc.sql("DELETE FROM llm_audit WHERE created_at < :cutoff")
                .param("cutoff", Timestamp.from(now.minus(retention.llmAudit())))
                .update();
    }
}
