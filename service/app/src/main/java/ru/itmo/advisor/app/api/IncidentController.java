package ru.itmo.advisor.app.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.itmo.advisor.app.config.SecurityFilter;
import ru.itmo.advisor.app.incident.AnalysisScheduler;
import ru.itmo.advisor.app.storage.FeedbackRepository;
import ru.itmo.advisor.app.storage.IncidentRepository;
import ru.itmo.advisor.domain.model.AnalysisResult;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.Feedback;
import ru.itmo.advisor.domain.model.HostRef;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.IncidentStatus;
import ru.itmo.advisor.domain.model.Tag;

/** API для модуля интерфейса Zabbix: список и карточка инцидента, повторный разбор, оценка инженером. */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentRepository incidents;
    private final FeedbackRepository feedback;
    private final AnalysisScheduler scheduler;
    private final Clock clock;

    public IncidentController(IncidentRepository incidents, FeedbackRepository feedback, AnalysisScheduler scheduler,
                              Clock clock) {
        this.incidents = incidents;
        this.feedback = feedback;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    public record Summary(UUID id, long problemEventId, String triggerName, int severity, List<HostRef> hosts,
                          Instant startedAt, Instant resolvedAt, IncidentStatus status, AnalysisStatus analysisStatus,
                          String topSuspect, String summary) {

        static Summary of(Incident i) {
            AnalysisResult a = i.analysis();
            return new Summary(i.id(), i.problemEventId(), i.triggerName(), i.severity(), i.hosts(), i.startedAt(),
                    i.resolvedAt(), i.status(), i.analysisStatus(), a == null ? null : a.topSuspect(),
                    a == null || a.explanation() == null ? null : a.explanation().summary());
        }
    }

    public record Details(UUID id, long problemEventId, String triggerName, int severity, List<HostRef> hosts,
                          List<Tag> tags, Instant startedAt, Instant resolvedAt, IncidentStatus status,
                          AnalysisStatus analysisStatus, String analysisError, AnalysisResult analysis,
                          List<Feedback> feedback) {
    }

    public record FeedbackRequest(@NotNull Feedback.Verdict verdict, String actualSource, String comment) {
    }

    @GetMapping
    public List<Summary> list(@RequestParam(defaultValue = "100") int limit) {
        return incidents.findRecent(Math.min(limit, 1000)).stream().map(Summary::of).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Details> get(@PathVariable UUID id) {
        return incidents.find(id)
                .map(i -> new Details(i.id(), i.problemEventId(), i.triggerName(), i.severity(), i.hosts(), i.tags(),
                        i.startedAt(), i.resolvedAt(), i.status(), i.analysisStatus(), i.analysisError(),
                        i.analysis(), feedback.findByIncident(i.id())))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/reanalyze")
    public ResponseEntity<Void> reanalyze(@PathVariable UUID id) {
        if (incidents.find(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        incidents.setAnalysisStatus(id, AnalysisStatus.PENDING);
        scheduler.schedule(id, Duration.ZERO);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/feedback")
    public ResponseEntity<Void> feedback(@PathVariable UUID id, @Valid @RequestBody FeedbackRequest body,
                                         @RequestHeader(value = SecurityFilter.USER_HEADER, required = false) String user) {
        if (incidents.find(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        feedback.save(new Feedback(id, body.verdict(), body.actualSource(), body.comment(), user, clock.instant()));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
