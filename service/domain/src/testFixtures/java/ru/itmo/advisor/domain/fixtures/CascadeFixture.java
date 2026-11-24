package ru.itmo.advisor.domain.fixtures;

import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.START;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.host;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.logSeries;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.standLogLine;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.sustainedChange;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.itmo.advisor.domain.model.LogSeries;
import ru.itmo.advisor.domain.model.MetricSeries;
import ru.itmo.advisor.domain.model.ServiceGraph;
import ru.itmo.advisor.domain.model.TimeWindow;
import ru.itmo.advisor.domain.port.ServiceGraphProvider.HostInfo;

/** Каскад gateway → orders → database. Время START соответствует проблеме gateway. */
public final class CascadeFixture {

    public static final Instant FAULT_START = START.minusSeconds(240);
    public static final ServiceGraph GRAPH = new ServiceGraph(Map.of(
            "gateway", Set.of("orders"), "orders", Set.of("database"), "database", Set.of()));
    public static final List<HostInfo> HOSTS = List.of(
            host("gateway", "orders"), host("orders", "database"), host("database"));

    private CascadeFixture() {
    }

    public static List<MetricSeries> metrics(TimeWindow window) {
        return List.of(sustainedChange("database", "Средняя задержка", window, FAULT_START, 50, 4000),
                sustainedChange("orders", "Доля 5xx", window, START.minusSeconds(150), 0.01, 0.6),
                sustainedChange("gateway", "Доля 5xx", window, START.minusSeconds(60), 0.01, 0.6));
    }

    public static List<LogSeries> logs(TimeWindow window) {
        var database = new ArrayList<LogSeries.Line>();
        var orders = new ArrayList<LogSeries.Line>();
        int n = 0;
        for (Instant t = window.from(); t.isBefore(window.to()); t = t.plusSeconds(10)) {
            String message = "Order " + (++n) + " read ok";
            database.add(standLogLine(t, "database", "INFO", "", "r.i.t.shop.OrderController", message));
            orders.add(standLogLine(t, "orders", "INFO", "", "r.i.t.shop.OrderController", message));
            if (!t.isBefore(FAULT_START)) {
                String reason = n % 2 == 0 ? "Deadlock detected while updating order status"
                        : "Pool exhausted: timeout waiting for connection";
                database.add(standLogLine(t, "database", "ERROR", "", "r.i.t.chaos.ChaosFilter",
                        "Unhandled exception while processing GET /api/orders/" + n + ": " + reason));
            }
        }
        return List.of(logSeries("database", database), logSeries("orders", orders));
    }
}
