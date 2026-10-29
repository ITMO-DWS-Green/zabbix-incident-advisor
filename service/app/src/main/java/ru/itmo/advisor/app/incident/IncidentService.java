package ru.itmo.advisor.app.incident;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.itmo.advisor.app.storage.IncidentRepository;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.IncidentStatus;
import ru.itmo.advisor.domain.model.ZabbixEvent;

/** События из коннектора: проблема — новый инцидент и отложенный разбор; восстановление — закрыть инцидент. */
@Service
public class IncidentService {

    private static final Logger LOG = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository incidents;
    private final AnalysisScheduler scheduler;

    public IncidentService(IncidentRepository incidents, AnalysisScheduler scheduler) {
        this.incidents = incidents;
        this.scheduler = scheduler;
    }

    public void onEvents(List<ZabbixEvent> events) {
        for (ZabbixEvent e : events) {
            if (e.isProblem()) {
                onProblem(e);
            } else if (e.problemEventId() != null) {
                incidents.resolve(e.problemEventId(), e.time());
            }
        }
    }

    private void onProblem(ZabbixEvent e) {
        Incident incident = new Incident(UUID.randomUUID(), e.eventid(), e.name() == null ? "" : e.name(),
                e.severity() == null ? 0 : e.severity(), e.hosts(), e.tags(), e.time(), null,
                IncidentStatus.OPEN, AnalysisStatus.PENDING, null, null);
        incidents.createIfAbsent(incident).ifPresent(created -> {
            LOG.info("Новый инцидент {}: {} на {}", created.id(), created.triggerName(), created.hostNames());
            scheduler.scheduleDefault(created.id());
        });
    }
}
