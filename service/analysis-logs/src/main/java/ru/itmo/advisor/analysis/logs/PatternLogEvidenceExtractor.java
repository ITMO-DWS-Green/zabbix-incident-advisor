package ru.itmo.advisor.analysis.logs;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.itmo.advisor.domain.model.IncidentContext;
import ru.itmo.advisor.domain.model.LogEvidence;
import ru.itmo.advisor.domain.model.LogSeries;
import ru.itmo.advisor.domain.port.LogEvidenceExtractor;

/**
 * Идея 2: из тысяч строк — несколько характерных. Строки приводятся к шаблонам (числа, id, время, UUID, IP
 * заменены), шаблоны считаются до «начала инцидента минус {@value #LEAD_MINUTES} мин» и после. Проблема в Zabbix
 * появляется позже самого сбоя (триггеры усредняют значения), поэтому первые строки ошибок написаны ещё до
 * {@code startedAt}; та же граница у {@code BaselineDeviationDetector}. В доказательства попадают новые шаблоны
 * и те, что участились в {@value #SURGE_FACTOR} раз и больше. Обычные INFO и ниже не берутся никогда;
 * на сервис — не больше {@code maxPerHost}, ERROR раньше WARN.
 *
 * <p>Уровень берётся из текста строки (формат Spring Boot и похожие): у файловых {@code log[]}-элементов Zabbix
 * поле severity пустое. Строка без распознанного уровня годится как доказательство, только если она новая или
 * участилась.
 */
public class PatternLogEvidenceExtractor implements LogEvidenceExtractor {

    public static final int DEFAULT_MAX_PER_HOST = 5;

    static final int LEAD_MINUTES = 5;
    static final int SURGE_FACTOR = 5;
    /** Меньше повторов — не «резкий рост», а случайность. */
    static final int SURGE_MIN_COUNT = 3;
    private static final int MAX_EXAMPLE = 300;
    /** Уровень стоит в начале строки, после даты; дальше по тексту те же слова — уже часть сообщения. */
    private static final int LEVEL_SEARCH_LIMIT = 80;

    private static final int NOISE = 0;
    private static final int UNKNOWN = 1;
    private static final int WARN = 2;
    private static final int ERROR = 3;

    private static final Pattern LEVEL =
            Pattern.compile("\\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|CRITICAL|SEVERE)\\b");
    /** Между префиксом (логгер, поток) и сообщением: {@code logger - msg} или {@code logger : msg}. */
    private static final Pattern MESSAGE_START = Pattern.compile(" [-:] ");

    private static final List<Replacement> PLACEHOLDERS = List.of(
            new Replacement("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b", "<UUID>"),
            new Replacement("\\b\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?", "<TIME>"),
            new Replacement("\\b\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?\\b", "<TIME>"),
            new Replacement("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b", "<IP>"),
            new Replacement("\\b0x[0-9a-fA-F]+\\b|\\b(?=[0-9a-f]*\\d)[0-9a-f]{12,}\\b", "<HEX>"),
            // Не после буквы: «150ms» → «<N>ms», но «oauth2» остаётся как есть.
            new Replacement("(?<![A-Za-z_])\\d+(?:\\.\\d+)?", "<N>"));

    private final int maxPerHost;

    public PatternLogEvidenceExtractor() {
        this(DEFAULT_MAX_PER_HOST);
    }

    public PatternLogEvidenceExtractor(int maxPerHost) {
        this.maxPerHost = maxPerHost;
    }

    @Override
    public List<LogEvidence> extract(IncidentContext context) {
        Instant boundary = splitPoint(context);
        long beforeSeconds = seconds(context.window().from(), boundary);
        long windowSeconds = seconds(boundary, context.window().to());

        Map<String, Map<String, Stats>> byHost = new LinkedHashMap<>();
        for (LogSeries series : context.logs()) {
            Map<String, Stats> patterns = byHost.computeIfAbsent(series.item().host(), h -> new LinkedHashMap<>());
            for (LogSeries.Line line : series.lines()) {
                Parsed parsed = parse(line.text());
                if (parsed.rank() == NOISE) {
                    continue;
                }
                patterns.computeIfAbsent(series.item().itemId() + '\0' + parsed.pattern(),
                                k -> new Stats(series.item().host(), series.item().itemId(), parsed.pattern()))
                        .add(line, parsed.rank(), line.time().isBefore(boundary));
            }
        }

        List<LogEvidence> result = new ArrayList<>();
        byHost.values().forEach(patterns -> patterns.values().stream()
                .filter(s -> s.inWindow > 0 && (s.before == 0 || s.surged(beforeSeconds, windowSeconds)))
                .sorted(Comparator.comparingInt((Stats s) -> -s.rank)
                        .thenComparing(s -> s.before != 0)
                        .thenComparingInt(s -> -s.inWindow)
                        .thenComparing(s -> s.firstSeen)
                        .thenComparing(s -> s.pattern))
                .limit(maxPerHost)
                .map(s -> new LogEvidence(s.host, s.itemId, s.pattern, s.example, s.inWindow, s.firstSeen,
                        s.before == 0))
                .forEach(result::add));
        return result;
    }

    /** Короткий lookback не должен оставить базовую линию пустой: тогда любая строка выглядела бы новой. */
    private static Instant splitPoint(IncidentContext context) {
        Instant started = context.incident().startedAt();
        Duration lead = Duration.ofMinutes(LEAD_MINUTES);
        Duration half = Duration.between(context.window().from(), started).dividedBy(2);
        return started.minus(lead.compareTo(half) < 0 ? lead : half);
    }

    /** Уровень и шаблон строки без префикса (время, уровень, сервис, trace id). */
    static Parsed parse(String text) {
        String line = text == null ? "" : text.strip();
        Matcher level = LEVEL.matcher(line.length() > LEVEL_SEARCH_LIMIT ? line.substring(0, LEVEL_SEARCH_LIMIT) : line);
        if (!level.find()) {
            return new Parsed(UNKNOWN, normalize(line));
        }
        Matcher start = MESSAGE_START.matcher(line);
        String message = start.find(level.end()) ? line.substring(start.end()) : line.substring(level.end());
        return new Parsed(rank(level.group(1)), normalize(message));
    }

    static String normalize(String message) {
        String result = message.strip();
        for (Replacement r : PLACEHOLDERS) {
            result = r.pattern.matcher(result).replaceAll(r.placeholder);
        }
        return result;
    }

    private static int rank(String level) {
        return switch (level) {
            case "TRACE", "DEBUG", "INFO" -> NOISE;
            case "WARN", "WARNING" -> WARN;
            default -> ERROR;
        };
    }

    private static long seconds(Instant from, Instant to) {
        return Math.max(1, Duration.between(from, to).getSeconds());
    }

    record Parsed(int rank, String pattern) {
    }

    private record Replacement(Pattern pattern, String placeholder) {
        Replacement(String regex, String placeholder) {
            this(Pattern.compile(regex), placeholder);
        }
    }

    private static final class Stats {
        final String host;
        final String itemId;
        final String pattern;
        int rank;
        int before;
        int inWindow;
        Instant firstSeen;
        String example;

        Stats(String host, String itemId, String pattern) {
            this.host = host;
            this.itemId = itemId;
            this.pattern = pattern;
        }

        void add(LogSeries.Line line, int lineRank, boolean beforeIncident) {
            rank = Math.max(rank, lineRank);
            if (beforeIncident) {
                before++;
                return;
            }
            inWindow++;
            if (firstSeen == null || line.time().isBefore(firstSeen)) {
                firstSeen = line.time();
                example = shorten(line.text().strip());
            }
        }

        /** Частота в окне инцидента выросла не меньше чем в {@link #SURGE_FACTOR} раз против времени до него. */
        boolean surged(long beforeSeconds, long windowSeconds) {
            return inWindow >= SURGE_MIN_COUNT
                    && (long) inWindow * beforeSeconds >= (long) SURGE_FACTOR * before * windowSeconds;
        }

        private static String shorten(String s) {
            return s.length() <= MAX_EXAMPLE ? s : s.substring(0, MAX_EXAMPLE - 1) + "…";
        }
    }
}
