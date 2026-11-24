package ru.itmo.advisor.analysis.ranking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.LogEvidence;
import ru.itmo.advisor.domain.model.MetricDeviation;
import ru.itmo.advisor.domain.model.ServiceGraph;
import ru.itmo.advisor.domain.model.Suspect;
import ru.itmo.advisor.domain.port.SuspectRanker;

/** Explainable heuristic, not a calibrated probability. Edges point from caller to dependency. */
public final class GraphEvidenceRanker implements SuspectRanker {
    private static final Duration CLOCK_TOLERANCE = Duration.ofSeconds(60);

    @Override
    public List<Suspect> rank(IncidentContext context, List<MetricDeviation> deviations, List<LogEvidence> logs) {
        Set<String> scope = new HashSet<>(context.scope());
        scope.addAll(context.incident().hostNames());
        Map<String, Evidence> evidence = new TreeMap<>();
        for (MetricDeviation d : deviations) {
            if (scope.contains(d.host()) && context.window().contains(d.startedAt())
                    && Double.isFinite(d.score()) && d.score() > 0) {
                Evidence e = evidence.computeIfAbsent(d.host(), k -> new Evidence());
                e.metrics.add(d);
                e.first = earlier(e.first, d.startedAt());
            }
        }
        for (LogEvidence log : logs) {
            if (scope.contains(log.host()) && log.count() > 0 && context.window().contains(log.firstSeen())) {
                Evidence e = evidence.computeIfAbsent(log.host(), k -> new Evidence());
                e.logs.add(log);
                // An existing pattern's first line does not date its increase in frequency.
                Instant onset = log.newInWindow() ? log.firstSeen() : context.incident().startedAt();
                e.first = earlier(e.first, onset);
            }
        }
        if (evidence.isEmpty()) {
            return context.incident().hostNames().stream().distinct().sorted()
                    .map(h -> new Suspect(h, 0.1, context.incident().startedAt(),
                            List.of("Хост проблемы; подтверждений в метриках и логах нет"))).toList();
        }
        Map<String, Set<String>> dependencies = new HashMap<>();
        evidence.keySet().forEach(h -> dependencies.put(h, reachable(context.graph(), h)));
        List<Suspect> result = new ArrayList<>();
        evidence.forEach((host, e) -> {
            // Compare timing within a connected component, not against an unrelated earlier incident.
            Set<String> component = context.graph().neighbourhood(host, Integer.MAX_VALUE);
            Instant earliest = evidence.entrySet().stream().filter(x -> component.contains(x.getKey()))
                    .map(x -> x.getValue().first).min(Comparator.naturalOrder()).orElse(e.first);
            double timing = 1 / (1 + Math.max(0, Duration.between(earliest, e.first).toMillis() / 1000.0) / 120);
            double strength = e.metrics.stream().mapToDouble(d -> d.score() / (d.score() + 3.5)).max().orElse(0);
            double logWeight = e.logs.stream().mapToDouble(l -> Math.min(1, Math.log1p(l.count()) / Math.log(101))
                    * (l.newInWindow() ? 1 : 0.5)).max().orElse(0);
            List<String> explained = new ArrayList<>(), causes = new ArrayList<>();
            evidence.forEach((other, o) -> {
                if (other.equals(host)) return;
                boolean otherDepends = dependencies.get(other).contains(host);
                boolean hostDepends = dependencies.get(host).contains(other);
                // A cycle supplies no directional evidence.
                if (otherDepends && !hostDepends && !e.first.isAfter(o.first.plus(CLOCK_TOLERANCE))) explained.add(other);
                if (hostDepends && !otherDepends && !o.first.isAfter(e.first.plus(CLOCK_TOLERANCE))) causes.add(other);
            });
            double topology = (double) explained.size() / (explained.size() + 1);
            double score = Math.max(0, Math.min(1, 0.25 * timing + 0.20 * strength + 0.15 * logWeight
                    + 0.40 * topology - (causes.isEmpty() ? 0 : 0.25)));
            List<String> reasons = new ArrayList<>();
            reasons.add(e.first.equals(earliest) ? "Первые признаки сбоя в своей группе связей: " + e.first
                    : "Первые признаки сбоя: " + e.first);
            e.metrics.stream().sorted(Comparator.comparingDouble(MetricDeviation::score).reversed()
                            .thenComparing(MetricDeviation::itemId)).limit(3)
                    .forEach(d -> reasons.add(d.metric() + ": " + number(d.expected()) + " → " + number(d.actual())
                            + " " + d.units() + "; сила отклонения " + number(d.score())));
            if (!explained.isEmpty()) reasons.add("От сервиса зависят " + String.join(", ", explained)
                    + "; направление связей и время согласуются с распространением сбоя");
            if (!causes.isEmpty()) reasons.add("Возможный симптом сбоя зависимости: " + String.join(", ", causes));
            e.logs.stream().sorted(Comparator.comparingInt(LogEvidence::count).reversed()
                            .thenComparing(LogEvidence::pattern)).limit(2)
                    .forEach(l -> reasons.add("В логах " + l.count() + " записей"
                            + (l.newInWindow() ? " нового шаблона: " : " шаблона: ") + shortText(l.pattern())));
            result.add(new Suspect(host, score, e.first, reasons));
        });
        result.sort(Comparator.comparingDouble(Suspect::score).reversed()
                .thenComparing(Suspect::firstDeviationAt).thenComparing(Suspect::host));
        return result;
    }

    private static Set<String> reachable(ServiceGraph graph, String host) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(graph.dependencies(host));
        while (!queue.isEmpty()) {
            String current = queue.remove();
            if (seen.add(current)) queue.addAll(graph.dependencies(current));
        }
        seen.remove(host);
        return seen;
    }

    private static Instant earlier(Instant a, Instant b) { return a == null || b.isBefore(a) ? b : a; }
    private static String number(double n) { return String.format(java.util.Locale.ROOT, "%.3g", n); }
    private static String shortText(String text) { return text.length() > 160 ? text.substring(0, 157) + "..." : text; }
    private static final class Evidence {
        private Instant first;
        private final List<MetricDeviation> metrics = new ArrayList<>();
        private final List<LogEvidence> logs = new ArrayList<>();
    }
}
