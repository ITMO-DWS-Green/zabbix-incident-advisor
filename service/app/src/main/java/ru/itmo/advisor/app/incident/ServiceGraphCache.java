package ru.itmo.advisor.app.incident;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.itmo.advisor.domain.model.ServiceGraph;
import ru.itmo.advisor.domain.port.ServiceGraphProvider;

/**
 * Карта связей для объединения проблем. Приём коннектора не ждёт Zabbix: карта строится при старте
 * и обновляется в фоне, когда устарела. Синхронно — только пока её ещё ни разу не удалось построить.
 */
@Component
public class ServiceGraphCache implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceGraphCache.class);
    static final Duration TTL = Duration.ofMinutes(1);

    private final ServiceGraphProvider graphs;
    private final ContextSource source;
    private final Clock clock;
    private final ExecutorService refresher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "graph-refresh");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean refreshing = new AtomicBoolean();

    private volatile ServiceGraph graph;
    private volatile Instant builtAt;

    public ServiceGraphCache(ServiceGraphProvider graphs, ContextSource source, Clock clock) {
        this.graphs = graphs;
        this.source = source;
        this.clock = clock;
    }

    /** Текущая карта; без неё — пустая: тогда объединяются только проблемы одного хоста. */
    public ServiceGraph get() {
        if (graph == null) {
            refresh();
        } else if (builtAt.plus(TTL).isBefore(clock.instant())) {
            refreshInBackground();
        }
        ServiceGraph current = graph;
        return current == null ? ServiceGraph.empty() : current;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshInBackground() {
        if (refreshing.compareAndSet(false, true)) {
            refresher.execute(() -> {
                try {
                    refresh();
                } finally {
                    refreshing.set(false);
                }
            });
        }
    }

    private synchronized void refresh() {
        try {
            graph = graphs.build(source.hosts());
            builtAt = clock.instant();
        } catch (RuntimeException e) {
            LOG.warn("Карта связей не построена, объединяю только по хосту: {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        refresher.shutdownNow();
    }
}
