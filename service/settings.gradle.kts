rootProject.name = "incident-advisor"

include(
    "zabbix-client",      // типизированный клиент Zabbix API — готов
    "domain",             // модель и интерфейсы бизнес-логики — готов
    "llm",                // LLM-клиент, маскирование, аудит — готов
    "analysis-graph",     // идея 6: карта связей сервисов
    "analysis-metrics",   // идея 1: отклонения метрик
    "analysis-logs",      // идея 2: доказательства из логов
    "analysis-ranking",   // идея 1: ранжирование подозреваемых, идея 8: причина и симптомы
    "analysis-explain",   // идея 3: объяснение
    "analysis-evaluation",// идея 7: точность на стенде
    "analysis-stream",    // идеи 11, 22: обработка потока значений
    "app",                // Spring Boot: конвейер, приём коннектора, БД, REST — готов
)
