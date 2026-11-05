package ru.itmo.advisor.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Настройки из переменных окружения — так, как их задаёт compose.yaml. */
class AdvisorPropertiesTest {

    @Test
    void mergeWindowIsReadFromEnvironment() {
        assertThat(bind(Map.of("ADVISOR_INCIDENT_MERGE_WINDOW", "5m")).incident().mergeWindow())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(bind(Map.of("ADVISOR_INCIDENT_MERGE_WINDOW", "0")).incident().mergeWindow())
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void mergeWindowDefaultsToOneMinute() {
        assertThat(bind(Map.of()).incident().mergeWindow()).isEqualTo(Duration.ofSeconds(60));
        assertThat(new AdvisorProperties(null, null, null, null, null).incident().mergeWindow())
                .isEqualTo(Duration.ofSeconds(60));
    }

    private static AdvisorProperties bind(Map<String, Object> env) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        environment.getPropertySources().addLast(new MapPropertySource("yml", Map.of("advisor.incident.merge-window", "60s")));
        ConfigurationPropertySources.attach(environment);
        return Binder.get(environment).bind("advisor", AdvisorProperties.class)
                .orElseGet(() -> new AdvisorProperties(null, null, null, null, null));
    }
}
