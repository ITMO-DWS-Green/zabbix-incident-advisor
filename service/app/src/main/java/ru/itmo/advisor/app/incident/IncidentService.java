package ru.itmo.advisor.app.incident;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.itmo.advisor.app.storage.IncidentRepository;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.Problem;
import ru.itmo.advisor.domain.model.ZabbixEvent;
import ru.itmo.advisor.domain.port.IncidentMergePolicy;

/**
 * События из коннектора. Проблема либо присоединяется к открытому инциденту (решает {@link IncidentMergePolicy}),
 * либо открывает новый инцидент с отложенным разбором. Восстановление закрывает проблему,
 * а инцидент — когда закрыты все его проблемы.
 */
@Service
public class IncidentService {

    private static final Logger LOG = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository incidents;
    private final AnalysisScheduler scheduler;
    private final AnalysisService analysis;
    private final IncidentMergePolicy merge;
    private final ServiceGraphCache graph;

    public IncidentService(IncidentRepository incidents, AnalysisScheduler scheduler, AnalysisService analysis,
                           IncidentMergePolicy merge, ServiceGraphCache graph) {
        this.incidents = incidents;
        this.scheduler = scheduler;
        this.analysis = analysis;
        this.merge = merge;
        this.graph = graph;
    }

    /** По одному: иначе две проблемы каскада, пришедшие одновременно, откроют два инцидента. */
    public synchronized void onEvents(List<ZabbixEvent> events) {
        for (ZabbixEvent e : events) {
            if (e.isProblem()) {
                onProblem(Problem.of(e));
            } else if (e.problemEventId() != null) {
                incidents.resolve(e.problemEventId(), e.time());
            }
        }
    }

    private void onProblem(Problem problem) {
        if (incidents.findByEventId(problem.eventId()).isPresent()) {
            return; // Zabbix повторил отправку
        }
        Optional<Incident> target = merge.mergeInto(problem, incidents.findOpen(), graph.get());
        if (target.isPresent()) {
            incidents.addProblem(target.get().id(), problem).ifPresent(joined -> {
                LOG.info("Проблема {} ({}) присоединена к инциденту {}: проблем {}", problem.eventId(),
                        problem.triggerName(), joined.id(), joined.problems().size());
                analysis.onProblemJoined(joined, problem.eventId());
            });
            return;
        }
        incidents.createIfAbsent(Incident.open(UUID.randomUUID(), problem)).ifPresent(created -> {
            LOG.info("Новый инцидент {}: {} на {}", created.id(), created.triggerName(), created.hostNames());
            scheduler.scheduleDefault(created.id());
        });
    }
}
