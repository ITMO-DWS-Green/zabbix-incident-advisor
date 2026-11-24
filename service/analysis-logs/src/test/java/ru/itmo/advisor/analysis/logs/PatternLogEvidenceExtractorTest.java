package ru.itmo.advisor.analysis.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.START;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.incident;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.logSeries;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.standLogLine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.domain.fixtures.IncidentFixtures;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.LogEvidence;
import ru.itmo.advisor.domain.model.LogSeries;
import ru.itmo.advisor.domain.model.ServiceGraph;
import ru.itmo.advisor.domain.model.TimeWindow;

/** Строки — в формате файлового лога test-server: {@code дата УРОВЕНЬ [сервис] [trace] логгер - сообщение}. */
class PatternLogEvidenceExtractorTest {

    private final PatternLogEvidenceExtractor extractor = new PatternLogEvidenceExtractor();

    @Test
    void exceptionLineFromDatabaseOn5xxFaultIsEvidenceAndInfoNoiseIsNot() {
        Lines database = new Lines("database");
        database.every(-840, 10, "INFO", "r.i.t.shop.OrderController", "Order {n} read ok");
        database.every(5, 5, "INFO", "r.i.t.shop.OrderController", "Order {n} read ok");
        database.at(5, "ERROR", "r.i.t.chaos.ChaosFilter",
                "Unhandled exception while processing GET /api/orders/{n}: Deadlock detected while updating order status");
        database.at(8, "ERROR", "r.i.t.chaos.ChaosFilter",
                "Unhandled exception while processing GET /api/orders/{n}: Deadlock detected while updating order status");
        database.at(11, "ERROR", "r.i.t.chaos.ChaosFilter",
                "Unhandled exception while processing POST /api/orders: Pool exhausted: timeout waiting for connection");

        List<LogEvidence> evidence = extractor.extract(context(database));

        assertThat(evidence).hasSize(2).allSatisfy(e -> {
            assertThat(e.host()).isEqualTo("database");
            assertThat(e.example()).contains(" ERROR [database] ").contains("Unhandled exception while processing");
            assertThat(e.newInWindow()).isTrue();
        });
        assertThat(evidence.get(0).pattern())
                .isEqualTo("Unhandled exception while processing GET /api/orders/<N>: Deadlock detected while updating order status");
        assertThat(evidence.get(0).count()).isEqualTo(2);
        assertThat(evidence.get(0).firstSeen()).isEqualTo(START.plusSeconds(5));
        assertThat(evidence).noneMatch(e -> e.example().contains(" INFO "));
    }

    @Test
    void faultThatStartedBeforeTheProblemWasRaisedIsStillNew() {
        // триггер avg(...,1m) сработал через минуты после начала сбоя — первые ошибки старше startedAt
        Lines database = new Lines("database");
        database.every(-840, 10, "INFO", "r.i.t.shop.OrderController", "Order {n} read ok");
        database.at(-200, "ERROR", "r.i.t.chaos.ChaosFilter", "Unhandled exception while processing GET /api/orders/{n}: Deadlock");
        database.at(-150, "ERROR", "r.i.t.chaos.ChaosFilter", "Unhandled exception while processing GET /api/orders/{n}: Deadlock");
        database.at(-100, "ERROR", "r.i.t.chaos.ChaosFilter", "Unhandled exception while processing GET /api/orders/{n}: Deadlock");
        database.at(10, "ERROR", "r.i.t.chaos.ChaosFilter", "Unhandled exception while processing GET /api/orders/{n}: Deadlock");

        assertThat(extractor.extract(context(database))).singleElement().satisfies(e -> {
            assertThat(e.newInWindow()).isTrue();
            assertThat(e.count()).isEqualTo(4);
            assertThat(e.firstSeen()).isEqualTo(START.minusSeconds(200));
        });
    }

    @Test
    void repeatedFaultStillShowsItsErrorsWhenThePatternIsAlreadyInTheBaseline() {
        // прошлый сбой закончился минуты назад: его строки в базовой линии, роста в 5 раз нет
        Lines database = new Lines("database");
        for (int s = -840; s < -400; s += 2) {
            database.at(s, "ERROR", "r.i.t.chaos.ChaosFilter", "Unhandled exception while processing GET /api/orders/{n}: Deadlock");
        }
        database.every(0, 3, "ERROR", "r.i.t.chaos.ChaosFilter", "Unhandled exception while processing GET /api/orders/{n}: Deadlock");

        assertThat(extractor.extract(context(database))).singleElement().satisfies(e -> {
            assertThat(e.newInWindow()).isFalse();
            assertThat(e.count()).isEqualTo(21);
            assertThat(e.example()).contains(" ERROR [database] ");
        });
    }

    @Test
    void strayKnownErrorsAndKnownWarningsAreNotFallbackEvidence() {
        Lines orders = new Lines("orders");
        for (int s = -840; s <= -400; s += 60) {
            orders.at(s, "ERROR", "r.i.t.A", "cache miss storm");
            orders.at(s, "WARN", "r.i.t.A", "slow query");
        }
        orders.at(10, "ERROR", "r.i.t.A", "cache miss storm");
        orders.at(20, "ERROR", "r.i.t.A", "cache miss storm");
        orders.every(0, 10, "WARN", "r.i.t.A", "slow query");

        assertThat(extractor.extract(context(orders))).isEmpty();
    }

    @Test
    void steadyBackgroundWarningsAndInfoGiveNoEvidence() {
        Lines orders = new Lines("orders");
        orders.every(-840, 30, "INFO", "r.i.t.shop.OrderController", "Order {n} create ok");
        orders.every(0, 5, "INFO", "r.i.t.shop.OrderController", "Order {n} create ok");
        orders.every(-840, 60, "WARN", "r.i.t.shop.OrderController", "Order {n} read took longer than expected");
        orders.at(30, "WARN", "r.i.t.shop.OrderController", "Order {n} read took longer than expected");

        assertThat(extractor.extract(context(orders))).isEmpty();
    }

    @Test
    void patternThatSurgedIsEvidenceButNotNew() {
        Lines orders = new Lines("orders");
        orders.every(-840, 90, "WARN", "r.i.t.client.Retry", "Retry {n} for payments");
        orders.every(0, 3, "WARN", "r.i.t.client.Retry", "Retry {n} for payments");

        List<LogEvidence> evidence = extractor.extract(context(orders));

        assertThat(evidence).singleElement().satisfies(e -> {
            assertThat(e.newInWindow()).isFalse();
            assertThat(e.count()).isEqualTo(25);
            assertThat(e.pattern()).isEqualTo("Retry <N> for payments");
        });
    }

    @Test
    void twoRepeatsAreNotASurge() {
        Lines orders = new Lines("orders");
        orders.at(-800, "WARN", "r.i.t.client.Retry", "Retry {n} for payments");
        orders.at(10, "WARN", "r.i.t.client.Retry", "Retry {n} for payments");
        orders.at(20, "WARN", "r.i.t.client.Retry", "Retry {n} for payments");

        assertThat(extractor.extract(context(orders))).isEmpty();
    }

    @Test
    void similarLinesDifferingInNumbersIdsTimeAndIpAreOnePattern() {
        Lines gateway = new Lines("gateway");
        gateway.at(3, "ERROR", "r.i.t.shop.OrderController",
                "Failed to read order 5: downstream orders unreachable (connect to 10.0.3.17:8080 failed at 12:00:03)");
        gateway.at(6, "ERROR", "r.i.t.shop.OrderController",
                "Failed to read order 91234: downstream orders unreachable (connect to 10.0.3.18:8080 failed at 12:00:06)");
        gateway.at(9, "ERROR", "r.i.t.shop.OrderController",
                "Failed to read order 7: downstream orders unreachable (connect to 10.0.3.17:9090 failed at 12:00:09)");
        gateway.at(12, "ERROR", "r.i.t.shop.OrderController",
                "Request 83f44a61-11bc-462b-a8e1-0c0637825412 rejected by node 0x7f3a9c21d0b8");
        gateway.at(15, "ERROR", "r.i.t.shop.OrderController",
                "Request 11793291-3cef-47ce-bf4d-de5b195db56e rejected by node 0x1a2b3c4d5e6f");

        List<LogEvidence> evidence = extractor.extract(context(gateway));

        assertThat(evidence).extracting(LogEvidence::pattern, LogEvidence::count).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(
                        "Failed to read order <N>: downstream orders unreachable (connect to <IP> failed at <TIME>)", 3),
                org.assertj.core.groups.Tuple.tuple("Request <UUID> rejected by node <HEX>", 2));
    }

    @Test
    void digitsInsideWordsStayAndUnitsAreGeneralised() {
        assertThat(PatternLogEvidenceExtractor.normalize("oauth2 token refresh took 150ms (v2, attempt 3)"))
                .isEqualTo("oauth2 token refresh took <N>ms (v2, attempt <N>)");
    }

    @Test
    void errorsComeBeforeWarningsAndHostIsCappedAtN() {
        Lines orders = new Lines("orders");
        for (int i = 0; i < 4; i++) {
            orders.at(10 + i, "WARN", "r.i.t.A", "warning kind " + (char) ('a' + i) + " happened");
            orders.at(20 + i, "WARN", "r.i.t.A", "warning kind " + (char) ('a' + i) + " happened");
        }
        orders.at(40, "ERROR", "r.i.t.A", "disk full");
        orders.at(41, "ERROR", "r.i.t.A", "connection lost");

        List<LogEvidence> evidence = new PatternLogEvidenceExtractor(3).extract(context(orders));

        assertThat(evidence).hasSize(3);
        assertThat(evidence.subList(0, 2)).extracting(LogEvidence::pattern)
                .containsExactlyInAnyOrder("disk full", "connection lost");
        assertThat(evidence.get(2).example()).contains(" WARN ");
    }

    @Test
    void sameTextOnTwoHostsIsReportedPerHost() {
        Lines orders = new Lines("orders");
        orders.at(10, "ERROR", "r.i.t.A", "Failed to read order 1: downstream database responded 500");
        Lines gateway = new Lines("gateway");
        gateway.at(12, "ERROR", "r.i.t.A", "Failed to read order 2: downstream database responded 500");

        assertThat(extractor.extract(context(orders, gateway))).extracting(LogEvidence::host)
                .containsExactly("orders", "gateway");
    }

    @Test
    void lineWithoutRecognisedLevelCountsOnlyWhenNewOrSurged() {
        Lines legacy = new Lines("legacy");
        legacy.raw(-600, "heartbeat from node 4");
        legacy.raw(20, "heartbeat from node 7");
        legacy.raw(30, "segfault in worker 2");

        assertThat(extractor.extract(context(legacy))).singleElement().satisfies(e -> {
            assertThat(e.pattern()).isEqualTo("segfault in worker <N>");
            assertThat(e.newInWindow()).isTrue();
        });
    }

    @Test
    void noLogsGiveNoEvidence() {
        assertThat(extractor.extract(context())).isEmpty();
    }

    private static IncidentContext context(Lines... hosts) {
        TimeWindow window = new TimeWindow(START.minus(Duration.ofMinutes(15)), START.plusSeconds(60));
        return IncidentFixtures.context(incident(1, "database", "database: доля 5xx выше 10%", START),
                window, ServiceGraph.empty(), Arrays.stream(hosts).map(h -> h.host).toList(), List.of(),
                Arrays.stream(hosts).map(Lines::series).toList());
    }

    /** Строки одного сервиса; {@code {n}} в тексте подставляется порядковым номером строки. */
    private static final class Lines {
        final String host;
        private final List<LogSeries.Line> lines = new ArrayList<>();
        private int counter;

        Lines(String host) {
            this.host = host;
        }

        void at(int secondsFromStart, String level, String logger, String message) {
            counter++;
            lines.add(standLogLine(START.plusSeconds(secondsFromStart), host, level, "t" + counter, logger,
                    message.replace("{n}", String.valueOf(counter))));
        }

        void every(int fromSeconds, int stepSeconds, String level, String logger, String message) {
            // до начала инцидента — по всему окну lookback, после — до конца окна разбора
            int to = fromSeconds < 0 ? -1 : 60;
            for (int s = fromSeconds; s <= to; s += stepSeconds) {
                at(s, level, logger, message);
            }
        }

        void raw(int secondsFromStart, String text) {
            lines.add(new LogSeries.Line(START.plusSeconds(secondsFromStart), text, null, null));
        }

        LogSeries series() {
            return logSeries(host, lines);
        }
    }
}
