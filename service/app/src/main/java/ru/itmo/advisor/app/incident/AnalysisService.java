package ru.itmo.advisor.app.incident;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.itmo.advisor.app.config.AdvisorProperties;
import ru.itmo.advisor.app.storage.IncidentRepository;
import ru.itmo.advisor.domain.model.AnalysisResult;
import ru.itmo.advisor.domain.model.AnalysisStatus;
import ru.itmo.advisor.domain.model.Explanation;
import ru.itmo.advisor.domain.model.Incident;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.LogEvidence;
import ru.itmo.advisor.domain.model.MetricDeviation;
import ru.itmo.advisor.domain.model.ServiceGraph;
import ru.itmo.advisor.domain.model.Suspect;
import ru.itmo.advisor.domain.port.CauseSymptomPolicy;
import ru.itmo.advisor.domain.port.ExplanationWriter;
import ru.itmo.advisor.domain.port.LogEvidenceExtractor;
import ru.itmo.advisor.domain.port.MetricDeviationDetector;
import ru.itmo.advisor.domain.port.ServiceGraphProvider;
import ru.itmo.advisor.domain.port.SuspectRanker;
import ru.itmo.advisor.domain.port.ZabbixWriter;

/**
 * Конвейер разбора — только порядок шагов, без логики:
 * граф → данные → отклонения метрик → логи → подозреваемые → объяснение → сохранить →
 * комментарий к каждой проблеме инцидента в Zabbix → причина и симптомы.
 *
 * <p>Разбор один на инцидент: в него попадают хосты всех проблем инцидента.
 */
@Service
public class AnalysisService {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisService.class);
    public static final String COMMENT_PREFIX = "[incident-advisor] ";

    private final IncidentRepository incidents;
    private final ContextSource source;
    private final ServiceGraphProvider graphs;
    private final MetricDeviationDetector detector;
    private final LogEvidenceExtractor logExtractor;
    private final SuspectRanker ranker;
    private final ExplanationWriter writer;
    private final CauseSymptomPolicy causes;
    private final ZabbixWriter zabbix;
    private final AdvisorProperties props;
    private final Clock clock;

    public AnalysisService(IncidentRepository incidents, ContextSource source, ServiceGraphProvider graphs,
                           MetricDeviationDetector detector, LogEvidenceExtractor logExtractor, SuspectRanker ranker,
                           ExplanationWriter writer, CauseSymptomPolicy causes, ZabbixWriter zabbix,
                           AdvisorProperties props, Clock clock) {
        this.incidents = incidents;
        this.source = source;
        this.graphs = graphs;
        this.detector = detector;
        this.logExtractor = logExtractor;
        this.ranker = ranker;
        this.writer = writer;
        this.causes = causes;
        this.zabbix = zabbix;
        this.props = props;
        this.clock = clock;
    }

    public void analyze(UUID incidentId) {
        Incident incident = incidents.find(incidentId).orElse(null);
        if (incident == null) {
            return;
        }
        incidents.setAnalysisStatus(incidentId, AnalysisStatus.RUNNING);
        AnalysisResult result;
        try {
            Instant now = clock.instant();
            ServiceGraph graph = graphs.build(source.hosts());
            IncidentContext context = source.load(incident, graph, now);
            List<MetricDeviation> deviations = detector.detect(context);
            List<LogEvidence> logs = logExtractor.extract(context);
            List<Suspect> suspects = ranker.rank(context, deviations, logs);
            Explanation explanation = writer.write(context, suspects, deviations, logs);
            result = new AnalysisResult(now, context.scope(), suspects, deviations, logs, explanation);
            incidents.saveAnalysis(incidentId, result);
            LOG.info("Разобран инцидент {} ({}, проблем: {}): источник {}", incidentId, incident.triggerName(),
                    incident.problems().size(), result.topSuspect());
        } catch (RuntimeException e) {
            LOG.warn("Разбор инцидента {} не удался: {}", incidentId, e.getMessage(), e);
            incidents.saveFailure(incidentId, e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        afterAnalysis(incident, result);
    }

    /**
     * Проблема присоединилась к инциденту, который уже разобран: разбор не повторяем,
     * только пишем его комментарием и к ней.
     */
    public void onProblemJoined(Incident incident, long eventId) {
        if (incident.analysisStatus() == AnalysisStatus.DONE && incident.analysis() != null) {
            comment(eventId, incident.analysis());
        }
    }

    /** Запись в Zabbix — отдельно: её ошибка не отменяет уже сохранённый разбор. */
    private void afterAnalysis(Incident incident, AnalysisResult result) {
        // Перечитываем: пока шёл разбор, к инциденту могли присоединиться проблемы.
        Incident analyzed = incidents.find(incident.id()).orElse(incident);
        analyzed.problemEventIds().forEach(eventId -> comment(eventId, result));
        try {
            causes.causeOf(analyzed, incidents.findOpen()).ifPresent(cause -> {
                zabbix.markSymptom(analyzed.problemEventId(), cause);
                LOG.info("Проблема {} помечена симптомом {}", analyzed.problemEventId(), cause);
            });
        } catch (RuntimeException e) {
            LOG.warn("Причина и симптомы для {} не записаны: {}", analyzed.problemEventId(), e.getMessage());
        }
    }

    private void comment(long eventId, AnalysisResult result) {
        if (!props.zabbix().comment() || result.explanation() == null) {
            return;
        }
        try {
            zabbix.comment(eventId, COMMENT_PREFIX + result.explanation().summary());
        } catch (RuntimeException e) {
            LOG.warn("Комментарий к проблеме {} не записан: {}", eventId, e.getMessage());
        }
    }
}
