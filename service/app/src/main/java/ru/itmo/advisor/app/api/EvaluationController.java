package ru.itmo.advisor.app.api;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.itmo.advisor.app.storage.IncidentRepository;
import ru.itmo.advisor.app.storage.LabelRepository;
import ru.itmo.advisor.domain.model.EvaluationReport;
import ru.itmo.advisor.domain.model.FaultLabel;
import ru.itmo.advisor.domain.port.QualityEvaluator;

/**
 * Точность разбора на стенде (идея 7).
 * {@code /api/v1/labels} и {@code /api/v1/telemetry} — тот же контракт, что у log-collector,
 * поэтому test-server с {@code COLLECTOR_URL=http://advisor:8080} шлёт разметку прямо сюда.
 */
@RestController
public class EvaluationController {

    private final LabelRepository labels;
    private final IncidentRepository incidents;
    private final QualityEvaluator evaluator;

    public EvaluationController(LabelRepository labels, IncidentRepository incidents, QualityEvaluator evaluator) {
        this.labels = labels;
        this.incidents = incidents;
        this.evaluator = evaluator;
    }

    @PostMapping("/api/v1/labels")
    public ResponseEntity<Void> label(@RequestBody FaultLabel label) {
        labels.save(label);
        return ResponseEntity.accepted().build();
    }

    /** Телеметрию test-server собирает сам Zabbix — здесь она не нужна. */
    @PostMapping("/api/v1/telemetry")
    public ResponseEntity<Void> telemetry() {
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/evaluation")
    public EvaluationReport evaluation(@RequestParam(required = false) String scenarioId) {
        return evaluator.evaluate(labels.find(scenarioId), incidents.findAll());
    }

    @GetMapping("/api/evaluation/scenarios")
    public List<String> scenarios() {
        return labels.scenarios();
    }
}
