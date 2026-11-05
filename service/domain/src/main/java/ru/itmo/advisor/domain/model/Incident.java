package ru.itmo.advisor.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Инцидент — группа связанных проблем Zabbix с одной причиной: каскад по карте связей даёт один инцидент
 * и один разбор. Поля «шапки» сводят проблемы вместе.
 *
 * @param problemEventId id события первой проблемы инцидента
 * @param triggerName    имя триггера первой проблемы
 * @param severity       наибольшая важность среди проблем
 * @param hosts          хосты всех проблем без повторов — они попадают в разбор
 * @param tags           теги всех проблем без повторов
 * @param startedAt      начало первой проблемы
 * @param resolvedAt     когда закрылась последняя проблема; {@code null}, пока открыта хоть одна
 * @param analysis       результат разбора; {@code null}, пока разбора нет
 * @param problems       проблемы инцидента по времени начала, первая — {@code problemEventId}; не пустой
 */
public record Incident(
        UUID id,
        long problemEventId,
        String triggerName,
        int severity,
        List<HostRef> hosts,
        List<Tag> tags,
        Instant startedAt,
        Instant resolvedAt,
        IncidentStatus status,
        AnalysisStatus analysisStatus,
        AnalysisResult analysis,
        String analysisError,
        List<Problem> problems) {

    public Incident {
        hosts = hosts == null ? List.of() : List.copyOf(hosts);
        tags = tags == null ? List.of() : List.copyOf(tags);
        problems = problems == null || problems.isEmpty()
                ? List.of(new Problem(problemEventId, triggerName, severity, hosts, tags, startedAt, resolvedAt))
                : problems.stream().sorted(Comparator.comparing(Problem::startedAt)).toList();
    }

    /** Инцидент из одной проблемы — как до объединения. */
    public Incident(UUID id, long problemEventId, String triggerName, int severity, List<HostRef> hosts,
                    List<Tag> tags, Instant startedAt, Instant resolvedAt, IncidentStatus status,
                    AnalysisStatus analysisStatus, AnalysisResult analysis, String analysisError) {
        this(id, problemEventId, triggerName, severity, hosts, tags, startedAt, resolvedAt, status, analysisStatus,
                analysis, analysisError, null);
    }

    /** Новый инцидент из первой проблемы: открыт, ждёт разбора. */
    public static Incident open(UUID id, Problem first) {
        return new Incident(id, first.eventId(), first.triggerName(), first.severity(), first.hosts(), first.tags(),
                first.startedAt(), first.resolvedAt(), IncidentStatus.OPEN, AnalysisStatus.PENDING, null, null,
                List.of(first));
    }

    /**
     * Инцидент с другим списком проблем: шапка пересчитывается. Первая по времени проблема становится
     * главной, статус — RESOLVED, когда закрыты все.
     */
    public Incident withProblems(List<Problem> newProblems) {
        List<Problem> sorted = newProblems.stream().sorted(Comparator.comparing(Problem::startedAt)).toList();
        Problem first = sorted.get(0);
        LinkedHashSet<HostRef> allHosts = new LinkedHashSet<>();
        LinkedHashSet<Tag> allTags = new LinkedHashSet<>();
        int maxSeverity = 0;
        for (Problem p : sorted) {
            allHosts.addAll(p.hosts());
            allTags.addAll(p.tags());
            maxSeverity = Math.max(maxSeverity, p.severity());
        }
        boolean allResolved = sorted.stream().noneMatch(Problem::isOpen);
        Instant resolved = allResolved
                ? sorted.stream().map(Problem::resolvedAt).max(Comparator.naturalOrder()).orElse(null)
                : null;
        return new Incident(id, first.eventId(), first.triggerName(), maxSeverity, List.copyOf(allHosts),
                List.copyOf(allTags), first.startedAt(), resolved,
                allResolved ? IncidentStatus.RESOLVED : IncidentStatus.OPEN, analysisStatus, analysis, analysisError,
                sorted);
    }

    /** Инцидент с ещё одной проблемой. */
    public Incident withProblem(Problem problem) {
        List<Problem> all = new ArrayList<>(problems);
        all.add(problem);
        return withProblems(all);
    }

    /** Технические имена хостов всех проблем. */
    public List<String> hostNames() {
        return hosts.stream().map(HostRef::host).toList();
    }

    /** id событий всех проблем инцидента — к каждой пишется комментарий. */
    public List<Long> problemEventIds() {
        return problems.stream().map(Problem::eventId).toList();
    }
}
