package ru.itmo.advisor.app.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Настройки из application.yml, префикс {@code advisor}. Переменные окружения: ADVISOR_ANALYSIS_DELAY и т. п. */
@ConfigurationProperties("advisor")
public record AdvisorProperties(Zabbix zabbix, Security security, Analysis analysis, Llm llm) {

    public AdvisorProperties {
        zabbix = zabbix == null ? new Zabbix(null, null, null, null) : zabbix;
        security = security == null ? new Security(null, null) : security;
        analysis = analysis == null ? new Analysis(null, null, null, null, null, null) : analysis;
        llm = llm == null ? new Llm(null, null, null, null, null) : llm;
    }

    /**
     * @param token     токен API; если пусто — читается из {@code tokenFile}
     * @param tokenFile файл с токеном, который пишет контейнер setup
     * @param comment   писать ли разбор комментарием к проблеме в Zabbix
     */
    public record Zabbix(String url, String token, String tokenFile, Boolean comment) {
        public Zabbix {
            url = blank(url) ? "http://localhost:8070" : url;
            comment = comment == null || comment;
        }
    }

    /**
     * @param uiToken        общий секрет между модулем интерфейса Zabbix и сервисом (заголовок X-Advisor-Token)
     * @param connectorToken токен, с которым коннектор Zabbix приходит к сервису (Authorization: Bearer)
     */
    public record Security(String uiToken, String connectorToken) {
    }

    /**
     * @param delay           через сколько после начала проблемы разбирать — чтобы набрались данные
     * @param lookback        сколько истории до начала проблемы брать в разбор
     * @param graphDepth      насколько далеко по графу связей брать соседей
     * @param maxItemsPerHost сколько элементов хоста брать максимум
     * @param historyLimit    сколько записей истории запрашивать за раз
     * @param threads         сколько разборов одновременно
     */
    public record Analysis(Duration delay, Duration lookback, Integer graphDepth, Integer maxItemsPerHost,
                           Integer historyLimit, Integer threads) {
        public Analysis {
            delay = delay == null ? Duration.ofSeconds(60) : delay;
            lookback = lookback == null ? Duration.ofMinutes(15) : lookback;
            graphDepth = graphDepth == null ? 2 : graphDepth;
            maxItemsPerHost = maxItemsPerHost == null ? 50 : maxItemsPerHost;
            historyLimit = historyLimit == null ? 20_000 : historyLimit;
            threads = threads == null ? 2 : threads;
        }
    }

    /** @param provider {@code stub} (по умолчанию, без сети) или {@code openai} (OpenAI-совместимый, в т. ч. Ollama) */
    public record Llm(String provider, String baseUrl, String apiKey, String model, Duration timeout) {
        public Llm {
            provider = blank(provider) ? "stub" : provider;
            baseUrl = blank(baseUrl) ? "http://ollama:11434/v1" : baseUrl;
            model = blank(model) ? "qwen2.5:3b" : model;
            timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
