package ru.itmo.advisor.app.incident;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.itmo.advisor.app.config.AdvisorProperties;
import ru.itmo.advisor.app.storage.IncidentRepository;
import ru.itmo.advisor.domain.model.HostRef;
import ru.itmo.advisor.domain.model.Tag;
import ru.itmo.advisor.domain.model.ZabbixEvent;
import ru.itmo.advisor.zabbix.ZabbixApi;
import ru.itmo.advisor.zabbix.model.Event;
import ru.itmo.advisor.zabbix.model.Problem;

/**
 * Догоняет события, которые коннектор не доставил (сервис лежал дольше, чем он повторяет отправку).
 * При старте и затем раз в {@code advisor.incident.catch-up-interval} сверяет {@code problem.get}
 * с базой и отдаёт разницу в {@link IncidentService#onEvents} — те же правила и защита от дублей, что у коннектора.
 *
 * <p>Граница: видны открытые и недавно закрытые проблемы ({@code problem.get recent=true}).
 * Сбой, целиком начавшийся и закончившийся во время простоя дольше этого окна, не восстанавливается.
 */
@Component
public class CatchUpService implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(CatchUpService.class);

    private final ZabbixApi zabbix;
    private final IncidentService incidentService;
    private final IncidentRepository incidents;
    private final Duration interval;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "catch-up");
        t.setDaemon(true);
        return t;
    });

    public CatchUpService(ZabbixApi zabbix, IncidentService incidentService, IncidentRepository incidents,
                          AdvisorProperties props) {
        this.zabbix = zabbix;
        this.incidentService = incidentService;
        this.incidents = incidents;
        this.interval = props.incident().catchUpInterval();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        executor.scheduleWithFixedDelay(this::safeCatchUp, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void safeCatchUp() {
        try {
            catchUp();
        } catch (RuntimeException e) {
            LOG.warn("Догон пропущенных событий не удался, повторим через {}: {}", interval, e.getMessage());
        }
    }

    void catchUp() {
        Map<Long, Problem> inZabbix = new LinkedHashMap<>();
        zabbix.problems(true).forEach(p -> inZabbix.put(Long.parseLong(p.eventid()), p));

        List<Long> missing = inZabbix.keySet().stream().filter(id -> incidents.findByEventId(id).isEmpty()).toList();
        List<ZabbixEvent> events = new ArrayList<>(newProblems(missing));
        int problems = events.size();
        events.addAll(recoveries(inZabbix, missing));
        if (events.isEmpty()) {
            return;
        }
        LOG.info("Догон: проблем {}, восстановлений {}", problems, events.size() - problems);
        incidentService.onEvents(events);
    }

    /** Хосты и теги problem.get не отдаёт — берём из event.get; по возрастанию времени, как их присылал бы коннектор. */
    private List<ZabbixEvent> newProblems(List<Long> missing) {
        if (missing.isEmpty()) {
            return List.of();
        }
        return zabbix.events(missing.stream().map(String::valueOf).toList()).stream()
                .sorted(Comparator.comparingLong(Event::clock).thenComparingInt(Event::ns))
                .map(CatchUpService::problemEvent)
                .toList();
    }

    /**
     * Закрытые в Zabbix проблемы, которые у нас ещё открыты или только что догнаны. Проблему, которой уже нет
     * в {@code problem.get}, спрашиваем напрямую: у её события есть {@code r_eventid}.
     */
    private List<ZabbixEvent> recoveries(Map<Long, Problem> inZabbix, List<Long> missing) {
        Set<Long> candidates = new LinkedHashSet<>(missing);
        incidents.findOpen().forEach(i -> i.problems().stream().filter(p -> p.isOpen())
                .forEach(p -> candidates.add(p.eventId())));

        Map<Long, String> recoveryOf = new LinkedHashMap<>();
        List<String> vanished = new ArrayList<>();
        for (Long id : candidates) {
            Problem p = inZabbix.get(id);
            if (p == null) {
                vanished.add(String.valueOf(id));
            } else if (!isOpen(p.recoveryEventId())) {
                recoveryOf.put(id, p.recoveryEventId());
            }
        }
        if (!vanished.isEmpty()) {
            zabbix.events(vanished).stream().filter(e -> !isOpen(e.recoveryEventId()))
                    .forEach(e -> recoveryOf.put(Long.parseLong(e.eventid()), e.recoveryEventId()));
        }
        if (recoveryOf.isEmpty()) {
            return List.of();
        }
        Map<String, Event> recoveryEvents = new HashMap<>();
        zabbix.events(recoveryOf.values().stream().distinct().toList())
                .forEach(e -> recoveryEvents.put(e.eventid(), e));
        List<ZabbixEvent> result = new ArrayList<>();
        recoveryOf.forEach((problemId, recoveryId) -> {
            Event e = recoveryEvents.get(recoveryId);
            if (e != null) {
                result.add(new ZabbixEvent(e.clock(), e.ns(), 0, Long.parseLong(recoveryId), problemId, null, null,
                        null, null, null));
            }
        });
        return result;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    private static boolean isOpen(String recoveryEventId) {
        return recoveryEventId == null || recoveryEventId.equals("0");
    }

    private static ZabbixEvent problemEvent(Event e) {
        return new ZabbixEvent(e.clock(), e.ns(), 1, Long.parseLong(e.eventid()), null, e.name(), e.severity(),
                e.hosts().stream().map(h -> new HostRef(h.host(), h.name())).toList(), List.of(),
                e.tags().stream().map(t -> new Tag(t.tag(), t.value())).toList());
    }
}
