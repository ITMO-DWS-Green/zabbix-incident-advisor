package ru.itmo.advisor.app.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.itmo.advisor.app.evaluation.LiveEvaluationService;
import ru.itmo.advisor.app.storage.LabelRepository;
import ru.itmo.advisor.domain.model.EvaluationReport;
import ru.itmo.advisor.domain.model.FaultLabel;

/**
 * Точность разбора на стенде (идея 7).
 * {@code /api/v1/labels} — тот же контракт, что у log-collector,
 * поэтому test-server с {@code COLLECTOR_URL=http://advisor:8080} шлёт разметку прямо сюда.
 */
@RestController
public class EvaluationController {

    // Conservative finite range supported by PostgreSQL TIMESTAMPTZ and java.sql.Timestamp.
    private static final Instant MIN_LABEL_TIME = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_LABEL_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");

    private final LabelRepository labels;
    private final LiveEvaluationService evaluationService;

    public EvaluationController(LabelRepository labels, LiveEvaluationService evaluationService) {
        this.labels = labels;
        this.evaluationService = evaluationService;
    }

    @PostMapping("/api/v1/labels")
    public ResponseEntity<?> label(@RequestBody FaultLabel label) {
        if (label == null || invalidLabelText(label.scenarioId()) || invalidLabelText(label.faultId())
                || invalidLabelText(label.type()) || invalidLabelText(label.source())
                || label.start() == null || label.end() == null || label.end().isBefore(label.start())) {
            return ResponseEntity.badRequest().build();
        }
        // Round like the locally verified pgjdbc versions, including before the epoch.
        // Check the input first to avoid overflowing Instant when adding 500 ns.
        if (!inLabelTimeRange(label.start()) || !inLabelTimeRange(label.end())) {
            return ResponseEntity.badRequest().build();
        }
        Instant start = label.start().plusNanos(500).truncatedTo(ChronoUnit.MICROS);
        Instant end = label.end().plusNanos(500).truncatedTo(ChronoUnit.MICROS);
        if (!inLabelTimeRange(start) || !inLabelTimeRange(end)) {
            return ResponseEntity.badRequest().build();
        }
        FaultLabel canonical = new FaultLabel(label.scenarioId(), label.faultId(), label.type(), label.source(),
                start, end);
        if (!labels.save(canonical)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "faultId already belongs to a different label: " + label.faultId()));
        }
        return ResponseEntity.accepted().build();
    }

    private static boolean invalidLabelText(String value) {
        return value == null || value.isBlank() || value.indexOf('\0') >= 0;
    }

    private static boolean inLabelTimeRange(Instant value) {
        return !value.isBefore(MIN_LABEL_TIME) && !value.isAfter(MAX_LABEL_TIME);
    }

    @GetMapping("/api/evaluation")
    public EvaluationReport evaluation(@RequestParam(required = false) String scenarioId) {
        return evaluationService.evaluate(scenarioId);
    }

    @ExceptionHandler(LiveEvaluationService.LimitExceeded.class)
    public ResponseEntity<Map<String, String>> evaluationTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "Evaluation input exceeds emergency limits"));
    }

    @ExceptionHandler(LiveEvaluationService.InvalidAnalysis.class)
    public ResponseEntity<Map<String, String>> invalidEvaluationAnalysis(LiveEvaluationService.InvalidAnalysis error) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", error.getMessage()));
    }

    @GetMapping("/api/evaluation/scenarios")
    public List<String> scenarios() {
        return evaluationService.scenarios();
    }
}
