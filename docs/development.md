# Разработка

## Правила зависимостей

- `analysis-*` зависят **только** от `domain`. Нужно что-то ещё (Zabbix, БД) — значит, нужен новый метод в интерфейсе `domain` или новое поле в `IncidentContext`. Это отдельный PR с предупреждением в чате команды: изменение `domain` затрагивает всех.
- Spring — только в `app`. Бизнес-логика — обычные классы, тестируются без контекста.
- Реализации выбираются в одном месте: `app/.../config/BusinessLogicConfig.java`.
- Изменения в Zabbix (комментарии, триггеры, значения) — только через `ZabbixWriter`. Изменения правил — только после подтверждения человеком.

## Модель данных

| Класс | Что это |
|---|---|
| `Incident` | Проблема Zabbix, которую разбирает сервис: `problemEventId`, хосты, теги, время, статус разбора |
| `IncidentContext` | Всё для разбора: окно времени, карта связей, сервисы в разборе (`scope`), `metrics` и `logs` по ним |
| `MetricSeries` | История числового элемента за окно: `MonitoredItem` + точки |
| `LogSeries` | Строки лог-элемента за окно. Лог-элемент — тип «лог» или текстовый с тегом `component: logs` |
| `ServiceGraph` | Кто от кого зависит; `neighbourhood`, `dependencies`, `dependents` |
| `MetricDeviation` | Отклонение метрики: было, стало, **когда началось**, сила |
| `LogEvidence` | Характерная строка: шаблон, пример, сколько раз, новая ли |
| `Suspect` | Подозреваемый: сервис, оценка, когда отклонился, причины — список по убыванию вероятности |
| `Explanation` | `summary` (уходит в Zabbix), `details` (Markdown для карточки), кто написал |
| `AnalysisResult` | Всё вместе; хранится в БД целиком (JSONB) |
| `FaultLabel` | Разметка сбоя от test-server — эталон для оценки |

## Конвейер

`app/.../incident/AnalysisService.java`:

```
ServiceGraphProvider.build(хосты Zabbix)
ContextSource.load(инцидент, граф)        ← история из Zabbix API, готово
MetricDeviationDetector.detect(контекст)
LogEvidenceExtractor.extract(контекст)
SuspectRanker.rank(контекст, отклонения, логи)
ExplanationWriter.write(...)
→ сохранить → комментарий в Zabbix → CauseSymptomPolicy → отметка симптома
```

Ошибка в любом шаге — разбор `FAILED` с текстом ошибки; в интерфейсе видно, что сломалось. Ошибка записи в Zabbix разбор не отменяет.

## Как тестировать свой модуль

Собрать `IncidentContext` руками и проверить результат — пример в `app/src/test/.../AnalysisServiceTest.java`: каскад «database тормозит → orders и gateway ошибаются» и ожидание, что первым подозреваемым будет database.

Проверка на живых данных: поднять `docker compose --profile stand up -d --build`, включить сбой на панели стенда, посмотреть карточку в Zabbix, оценку точности — `GET /api/evaluation`.

## LLM

- По умолчанию `stub`: модели нет, `ExplanationWriter` пишет по шаблону. Ничего не уходит наружу.
- `ADVISOR_LLM_PROVIDER=openai` — любой OpenAI-совместимый API. Локально: `--profile ollama`.
- Каждый вызов проходит маскирование (IP, e-mail, URL и слова из `Request.sensitive` → псевдонимы) и пишется в таблицу `llm_audit` в замаскированном виде.
- LLM только пересказывает найденные факты. Искать причину — задача детекторов и ранжирования.

## Где что в Zabbix

| Что | Как настроено |
|---|---|
| Токен API сервиса | `setup.py` создаёт API-токен для Admin и пишет его в общий том `/secrets` |
| Коннектор событий | Все события → `/zabbix/connector/events`, Bearer-токен |
| Коннектор значений | Элементы с тегом `advisor` → `/zabbix/connector/values` |
| Модуль интерфейса | Папка смонтирована в zabbix-web, модуль включён через `module.create` |
| Хосты стенда | Группа `test-server`, теги `depends_on`, метрики и триггеры |
| Кэш конфигурации | `ZBX_CACHEUPDATEFREQUENCY=10` — новые триггеры работают через 10 с |

Для боевого Zabbix: отдельный пользователь с правами чтения на нужные группы и записи триггеров, свой API-токен, свои секреты `ADVISOR_UI_TOKEN` и `ADVISOR_CONNECTOR_TOKEN`, адрес сервиса в `zabbix-module/incident-advisor/config.php`.

Точки расширения Zabbix и API в целом — [architecture/zabbix-integration.md](architecture/zabbix-integration.md), обзор — [docs/README.md](README.md).
