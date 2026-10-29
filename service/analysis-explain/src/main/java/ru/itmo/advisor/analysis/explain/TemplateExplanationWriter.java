package ru.itmo.advisor.analysis.explain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import ru.itmo.advisor.domain.model.Explanation;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.LogEvidence;
import ru.itmo.advisor.domain.model.MetricDeviation;
import ru.itmo.advisor.domain.model.Suspect;
import ru.itmo.advisor.domain.port.ExplanationWriter;
import ru.itmo.advisor.domain.port.LlmClient;

/**
 * Идея 3, образец: собирает факты в текст по шаблону. Если LLM включена — просит её
 * переписать те же факты понятнее, не добавляя новых.
 *
 * <p>TODO идея 3: промпт, формат ссылок на графики и строки, проверка, что LLM не выдумала фактов.
 */
public class TemplateExplanationWriter implements ExplanationWriter {

    private static final String SYSTEM = """
            Ты помогаешь дежурному инженеру. Перепиши факты о сбое коротко и по-русски.
            Используй только переданные факты, ничего не добавляй. Первая фраза — вероятный источник.
            """;

    private final LlmClient llm;

    public TemplateExplanationWriter(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public Explanation write(IncidentContext context, List<Suspect> suspects, List<MetricDeviation> deviations,
                             List<LogEvidence> logs) {
        String facts = facts(context, suspects, deviations, logs);
        String summary = suspects.isEmpty()
                ? "Источник не определён: отклонений метрик не найдено."
                : "Вероятный источник — " + suspects.get(0).host() + ". " + String.join("; ", suspects.get(0).reasons());
        if (!llm.enabled()) {
            return new Explanation(summary, facts, "template");
        }
        Set<String> sensitive = new HashSet<>(context.scope());
        var response = llm.complete(LlmClient.Request.of("explain", SYSTEM, facts, sensitive));
        return new Explanation(firstSentences(response.text()), response.text(), response.model());
    }

    private static String facts(IncidentContext context, List<Suspect> suspects, List<MetricDeviation> deviations,
                                List<LogEvidence> logs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Проблема: ").append(context.incident().triggerName()).append('\n');
        sb.append("Сервисы в разборе: ").append(String.join(", ", context.scope())).append("\n\n");
        sb.append("Подозреваемые:\n");
        suspects.forEach(s -> sb.append("- ").append(s.host()).append(", отклонение с ").append(s.firstDeviationAt())
                .append(": ").append(String.join("; ", s.reasons())).append('\n'));
        sb.append("\nОтклонения метрик:\n");
        deviations.forEach(d -> sb.append("- ").append(d.host()).append(" / ").append(d.metric())
                .append(": обычно ").append(d.expected()).append(", стало ").append(d.actual())
                .append(" с ").append(d.startedAt()).append('\n'));
        if (!logs.isEmpty()) {
            sb.append("\nЛоги:\n");
            sb.append(logs.stream().map(l -> "- " + l.host() + ": " + l.example() + " (" + l.count() + " раз)")
                    .collect(Collectors.joining("\n")));
        }
        return sb.toString();
    }

    private static String firstSentences(String text) {
        String[] parts = text.split("(?<=[.!?])\\s+");
        return String.join(" ", List.of(parts).subList(0, Math.min(2, parts.length)));
    }
}
