package ru.itmo.advisor.app.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.itmo.advisor.app.config.AdvisorProperties.Retention;
import ru.itmo.advisor.app.config.SecurityFilter;
import ru.itmo.advisor.app.storage.RetentionSettingsRepository;

@RestController
@RequestMapping("/api/settings/retention")
public class RetentionSettingsController {

    private final RetentionSettingsRepository settings;
    private final SecurityFilter security;

    public RetentionSettingsController(RetentionSettingsRepository settings, SecurityFilter security) {
        this.settings = settings;
        this.security = security;
    }

    @GetMapping
    public ResponseEntity<?> get(HttpServletRequest request) {
        if (!security.canManageRetention(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(Settings.of(settings.get()));
    }

    @PostMapping
    public ResponseEntity<?> save(HttpServletRequest request, @RequestBody Settings body) {
        if (!security.canManageRetention(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Retention retention;
        try {
            retention = new Retention(parse(body.incidents()), parse(body.llmAudit()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "Укажи положительные сроки, например 30d или 12h."));
        }
        settings.save(retention);
        return ResponseEntity.ok(Settings.of(retention));
    }

    private static Duration parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Пустой срок хранения");
        }
        return DurationStyle.detectAndParse(value.trim(), ChronoUnit.DAYS);
    }

    public record Settings(String incidents, String llmAudit) {
        static Settings of(Retention retention) {
            return new Settings(format(retention.incidents()), format(retention.llmAudit()));
        }

        private static String format(Duration duration) {
            long days = duration.toDays();
            return duration.equals(Duration.ofDays(days)) ? days + "d" : duration.toString();
        }
    }
}
