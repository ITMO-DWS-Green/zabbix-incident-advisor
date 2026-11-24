package ru.itmo.advisor.app.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.app.config.AdvisorProperties;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.HostRef;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.IncidentStatus;
import ru.itmo.advisor.domain.model.ServiceGraph;
import ru.itmo.advisor.zabbix.ZabbixApi;
import ru.itmo.advisor.zabbix.ZabbixApiException;
import ru.itmo.advisor.zabbix.model.Trend;
import ru.itmo.advisor.zabbix.model.HistoryRecord;
import ru.itmo.advisor.zabbix.model.Host;
import ru.itmo.advisor.zabbix.model.Item;
import ru.itmo.advisor.zabbix.model.Tag;
import ru.itmo.advisor.zabbix.model.ValueType;

/** Лог-элементы стенда (тип «лог» с тегом component: logs) попадают в IncidentContext.logs(). */
class ZabbixContextSourceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    @Test
    void logItemsOfStandHostsEndUpInContextLogs() {
        ZabbixApi zabbix = mock(ZabbixApi.class);
        when(zabbix.hostsByName(any())).thenReturn(List.of(new Host("10", "database", "database", List.of(), List.of())));
        when(zabbix.items(any())).thenReturn(List.of(
                item("1", ValueType.LOG, "log[/var/log/stand/database.log,,,100]", List.of(new Tag("component", "logs"))),
                item("2", ValueType.TEXT, "testserver.status", List.of()),
                item("3", ValueType.FLOAT, "testserver.error_rate", List.of())));
        when(zabbix.latestHistory(eq(ValueType.LOG), eq(List.of("1")), any(), any(), anyInt())).thenReturn(List.of(
                new HistoryRecord("1", NOW.minusSeconds(30).getEpochSecond(), 0,
                        "ERROR [database] ChaosFilter - Unhandled exception: Pool exhausted", null, null, null, null)));
        when(zabbix.history(eq(ValueType.FLOAT), any(), any(), any(), anyInt())).thenReturn(List.of());

        IncidentContext context = new ZabbixContextSource(zabbix, new AdvisorProperties(null, null, null, null, null, null))
                .load(incident(), new ServiceGraph(Map.of("database", Set.of())), NOW);

        assertThat(context.logs()).hasSize(1);
        assertThat(context.logsOf("database").get(0).lines())
                .extracting(l -> l.text()).singleElement().asString().contains("Pool exhausted");
        assertThat(context.logs().get(0).item().key()).startsWith("log[");
    }

    @Test
    void loadsOnlyCompletedHistoricalTrendHours() {
        ZabbixApi zabbix = numericSource();
        Instant end = Instant.parse("2026-10-30T09:00:00Z");
        when(zabbix.trends(any(), any(), any())).thenReturn(List.of(
                new Trend("3", end.minusSeconds(3600).getEpochSecond(), 60, 1, 2, 3)));
        IncidentContext context = source(zabbix);
        assertThat(context.metrics()).hasSize(1);
        assertThat(context.metrics().getFirst().trends()).hasSize(1);
        assertThat(context.metrics().getFirst().trends().getFirst().avg()).isEqualTo(2);
        verify(zabbix).trends(List.of("3"), end.minusSeconds(28 * 86400), end.minusSeconds(1));
    }

    @Test
    void unavailableTrendsDoNotDiscardRawHistory() {
        ZabbixApi zabbix = numericSource();
        when(zabbix.trends(any(), any(), any())).thenThrow(new ZabbixApiException("denied", -32602, null));
        IncidentContext context = source(zabbix);
        assertThat(context.metrics().getFirst().points()).hasSize(1);
        assertThat(context.metrics().getFirst().trends()).isEmpty();
    }

    private static ZabbixApi numericSource() {
        ZabbixApi zabbix = mock(ZabbixApi.class);
        when(zabbix.hostsByName(any())).thenReturn(List.of(new Host("10", "database", "database", List.of(), List.of())));
        when(zabbix.items(any())).thenReturn(List.of(item("3", ValueType.FLOAT, "latency", List.of())));
        when(zabbix.history(eq(ValueType.FLOAT), any(), any(), any(), anyInt())).thenReturn(List.of(
                new HistoryRecord("3", NOW.minusSeconds(30).getEpochSecond(), 0, "2", null, null, null, null)));
        return zabbix;
    }

    private static IncidentContext source(ZabbixApi zabbix) {
        return new ZabbixContextSource(zabbix, new AdvisorProperties(null, null, null, null, null, null))
                .load(incident(), ServiceGraph.empty(), NOW);
    }

    private static Item item(String id, ValueType type, String key, List<Tag> tags) {
        return new Item(id, "10", key, key, type.code(), "", tags, List.of(new ru.itmo.advisor.zabbix.model.HostRef("10", "database", "database")), null, null, 0, 0);
    }

    private static Incident incident() {
        return new Incident(UUID.randomUUID(), 1L, "database: доля 5xx выше 10%", 3,
                List.of(new HostRef("database", "database")), List.of(), NOW.minusSeconds(60), null,
                IncidentStatus.OPEN, AnalysisStatus.PENDING, null, null);
    }
}
