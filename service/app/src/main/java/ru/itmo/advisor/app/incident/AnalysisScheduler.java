package ru.itmo.advisor.app.incident;

import java.time.Duration;
import java.util.UUID;
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
import ru.itmo.advisor.domain.model.AnalysisStatus;

/** Откладывает разбор, чтобы набрались данные, и выполняет его в своём пуле потоков. */
@Component
public class AnalysisScheduler implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisScheduler.class);

    private final AnalysisService analysis;
    private final IncidentRepository incidents;
    private final Duration delay;
    private final ScheduledExecutorService executor;

    public AnalysisScheduler(AnalysisService analysis, IncidentRepository incidents, AdvisorProperties props) {
        this.analysis = analysis;
        this.incidents = incidents;
        this.delay = props.analysis().delay();
        this.executor = Executors.newScheduledThreadPool(props.analysis().threads(), r -> {
            Thread t = new Thread(r, "analysis");
            t.setDaemon(true);
            return t;
        });
    }

    public void scheduleDefault(UUID incidentId) {
        schedule(incidentId, delay);
    }

    public void schedule(UUID incidentId, Duration after) {
        executor.schedule(() -> run(incidentId), after.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** После перезапуска — доразобрать то, что не успели. */
    @EventListener(ApplicationReadyEvent.class)
    public void resumePending() {
        var pending = new java.util.ArrayList<>(incidents.findByAnalysisStatus(AnalysisStatus.PENDING));
        pending.addAll(incidents.findByAnalysisStatus(AnalysisStatus.RUNNING));
        pending.forEach(i -> schedule(i.id(), Duration.ofSeconds(5)));
        if (!pending.isEmpty()) {
            LOG.info("Возобновлено разборов после перезапуска: {}", pending.size());
        }
    }

    private void run(UUID incidentId) {
        try {
            analysis.analyze(incidentId);
        } catch (RuntimeException e) {
            LOG.error("Сбой разбора {}", incidentId, e);
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
