package ru.itmo.advisor.llm;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Идея 4. Заменяет чувствительные данные псевдонимами перед отправкой в LLM и возвращает их в ответе.
 * Всегда маскирует IPv4, e-mail и URL; плюс переданные слова — обычно имена хостов.
 */
public class Masker {

    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>)]+");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    /** Результат маскирования: текст и словарь «псевдоним → исходное». */
    public record Masked(String text, Map<String, String> aliases) {
    }

    /** Один словарь на весь запрос: одинаковые значения в разных сообщениях получают один псевдоним. */
    public static final class Session {
        private final Map<String, String> byOriginal = new LinkedHashMap<>();
        private final Map<String, String> byAlias = new LinkedHashMap<>();
        private final Set<String> sensitive;

        Session(Set<String> sensitive) {
            this.sensitive = sensitive;
        }

        public String mask(String text) {
            String result = text;
            for (String term : sensitive.stream().filter(s -> !s.isBlank())
                    .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
                result = result.replace(term, alias("HOST", term));
            }
            result = replaceAll(URL, result, "URL");
            result = replaceAll(EMAIL, result, "EMAIL");
            result = replaceAll(IPV4, result, "IP");
            return result;
        }

        public String unmask(String text) {
            String result = text;
            // длинные псевдонимы первыми, чтобы HOST_10 не превратился в «HOST_1» + «0»
            for (String alias : byAlias.keySet().stream()
                    .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
                result = result.replace(alias, byAlias.get(alias));
            }
            return result;
        }

        public Map<String, String> aliases() {
            return Map.copyOf(byAlias);
        }

        private String replaceAll(Pattern pattern, String text, String kind) {
            Matcher m = pattern.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                if (m.group().matches("[A-Z]+_\\d+")) {
                    continue;
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(alias(kind, m.group())));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private String alias(String kind, String original) {
            return byOriginal.computeIfAbsent(original, o -> {
                String alias = kind + "_" + (byOriginal.size() + 1);
                byAlias.put(alias, o);
                return alias;
            });
        }
    }

    public Session session(Set<String> sensitive) {
        return new Session(sensitive);
    }
}
