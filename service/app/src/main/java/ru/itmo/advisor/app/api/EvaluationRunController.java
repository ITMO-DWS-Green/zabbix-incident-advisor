package ru.itmo.advisor.app.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.itmo.advisor.app.evaluation.EvaluationRun;
import ru.itmo.advisor.app.evaluation.EvaluationRunService;

@RestController
@RequestMapping("/api/evaluation/runs")
public class EvaluationRunController {
    private final EvaluationRunService service;

    public EvaluationRunController(EvaluationRunService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EvaluationRun> create(@RequestBody JsonNode body) {
        if (body == null || !body.isObject() || !body.hasNonNull("from") || !body.get("from").isTextual()
                || !body.hasNonNull("to") || !body.get("to").isTextual()) {
            throw new EvaluationRunService.Validation("Укажите from и to как строки ISO-8601 с часовым поясом");
        }
        if (body.size() != 2) {
            throw new EvaluationRunService.Validation("Ожидаются только поля from и to");
        }
        Instant from;
        Instant to;
        try {
            from = Instant.parse(body.get("from").textValue());
            to = Instant.parse(body.get("to").textValue());
        } catch (DateTimeParseException e) {
            throw new EvaluationRunService.Validation("from и to должны быть ISO-8601 timestamps с часовым поясом");
        }
        EvaluationRun run = service.create(from, to);
        return ResponseEntity.created(URI.create("/api/evaluation/runs/" + run.id())).body(run);
    }

    @GetMapping
    public List<EvaluationRun.Summary> list(@RequestParam(defaultValue = "50") int limit,
                                             @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new EvaluationRunService.Validation("limit должен быть от 1 до 100, offset неотрицательным");
        }
        return service.list(limit, offset);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EvaluationRun> get(@PathVariable UUID id) {
        return service.find(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(EvaluationRunService.Validation.class)
    public ResponseEntity<Map<String, String>> invalid(EvaluationRunService.Validation e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> malformed(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Некорректный JSON или параметр запроса"));
    }
}
