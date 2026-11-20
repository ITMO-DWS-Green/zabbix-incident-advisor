# Настройки сервиса

Значения по умолчанию заданы в [`application.yml`](../service/app/src/main/resources/application.yml) и [`AdvisorProperties`](../service/app/src/main/java/ru/itmo/advisor/app/config/AdvisorProperties.java). Для запуска через Compose переменная должна быть передана в окружение контейнера; текущий набор — в [`compose.yaml`](../compose.yaml).

| Переменная | По умолчанию | Что задаёт |
|---|---|---|
| `ADVISOR_ANALYSIS_DELAY` | `60s` | задержка перед разбором после приёма новой проблемы |
| `ADVISOR_ANALYSIS_LOOKBACK` | `15m` | сколько истории до начала брать в разбор |
| `ADVISOR_ANALYSIS_GRAPH_DEPTH` | `2` | насколько далеко по карте связей брать соседей |
| `ADVISOR_INCIDENT_CATCH_UP_INTERVAL` | `5m` | как часто сверять `problem.get` с базой, чтобы догнать события, которые коннектор не доставил (первая сверка — при старте); `0` — выключить |
| `ADVISOR_INCIDENT_MERGE_WINDOW` | `60s` | окно объединения: проблема на связанном сервисе, начавшаяся не дальше этого от начала инцидента, входит в него; `0` — не объединять |
| `ADVISOR_RETENTION_INCIDENTS` | `90d` | срок хранения инцидента после закрытия |
| `ADVISOR_RETENTION_LLM_AUDIT` | `90d` | срок хранения записи журнала LLM с момента её создания |
| `ADVISOR_ZABBIX_COMMENT` | `true` | писать ли разбор комментарием к проблеме |
| `ADVISOR_LLM_PROVIDER` | `stub` | `stub` — без модели; `openai` — OpenAI-совместимый Chat Completions API |
| `ADVISOR_LLM_BASE_URL` | `http://ollama:11434/v1` | базовый адрес API без `/chat/completions`: Ollama, OpenRouter, Groq, LM Studio и др. |
| `ADVISOR_LLM_MODEL` | `qwen2.5:3b` | имя модели у выбранного провайдера |
| `ADVISOR_LLM_API_KEY` | — | ключ провайдера; для локальной Ollama можно оставить пустым |
| `ADVISOR_UI_TOKEN` | `dev-ui-token` | секрет между модулем интерфейса и сервисом |
| `ADVISOR_CONNECTOR_TOKEN` | `dev-connector-token` | токен, с которым приходит коннектор Zabbix |
| `TELEGRAM_BOT_TOKEN` | — | токен бота для оповещения о разборе; задаётся контейнеру `setup`, в репозитории не хранится. Без него тип оповещения создаётся выключенным |
| `TELEGRAM_API_URL` | `https://api.telegram.org/bot` | куда вебхук шлёт сообщения; на стенде — заглушка `http://telegram-stub:8080/bot` |
| `TELEGRAM_CHAT_IDS` | — | id групповых чатов команд: `platform:-1001,orders:-1002,…`. Незаданная команда получает id-заглушку |

LLM выключена по умолчанию (`stub`). Чтобы подключить бесплатную/условно бесплатную нейронку, используйте OpenAI-compatible API:

```bash
# OpenRouter: бесплатные модели обычно имеют суффикс :free.
ADVISOR_LLM_PROVIDER=openai
ADVISOR_LLM_BASE_URL=https://openrouter.ai/api/v1
ADVISOR_LLM_MODEL=meta-llama/llama-3.2-3b-instruct:free
ADVISOR_LLM_API_KEY=<ключ OpenRouter>

# Groq: бесплатные лимиты зависят от аккаунта.
ADVISOR_LLM_PROVIDER=openai
ADVISOR_LLM_BASE_URL=https://api.groq.com/openai/v1
ADVISOR_LLM_MODEL=llama-3.1-8b-instant
ADVISOR_LLM_API_KEY=<ключ Groq>
```

Проверить OpenRouter-ключ можно без запуска стенда: `ADVISOR_LLM_API_KEY=<ключ> python3 .github/scripts/check_openrouter.py`. В GitHub Actions положите ключ в repository secret `OPENROUTER_API_KEY` и запустите workflow **CI** с включённым параметром `openrouter_check` вручную.

Локально можно использовать профиль Ollama: [инструкция запуска](getting-started.md). Значение в таблице — резервное значение Java-сервиса; [`.env.example`](../.env.example) содержит готовые примеры. Перед внешней LLM факты инцидента проходят маскирование и пишутся в журнал `llm_audit`, но это всё равно внешний сервис — включайте его осознанно.

PHP-модуль читает `INCIDENT_ADVISOR_URL` и `INCIDENT_ADVISOR_TOKEN` из окружения `zabbix-web`. Его токен должен совпадать с `ADVISOR_UI_TOKEN` сервиса; исправление автоматической передачи через Compose отслеживается в [#82](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/82).

В **Monitoring → Incident advisor → Хранение данных** администратор Zabbix может изменить оба срока. Сохранённые в базе значения имеют приоритет над переменными окружения и применяются при ближайшей очистке без перезапуска сервиса. До первого сохранения действуют значения из окружения.

В Compose отдельный токен для настроек создаёт контейнер `setup`. При установке вне Compose задай один и тот же секрет через `ADVISOR_RETENTION_ADMIN_TOKEN` в Java-сервисе и `INCIDENT_ADVISOR_RETENTION_TOKEN` в окружении PHP-модуля.

Очистка выполняется ежедневно в 03:00 UTC. Сроки хранения задаются положительными интервалами, например `30d` или `12h`. Открытые инциденты сохраняются до закрытия. Вместе с просроченным инцидентом удаляются его проблемы и оценки инженеров. Разметка стенда хранится отдельно.

## API сервиса

| Метод | Путь | Кто вызывает |
|---|---|---|
| POST | `/zabbix/connector/events` | коннектор Zabbix: события |
| POST | `/zabbix/connector/values` | коннектор Zabbix: значения элементов с тегом `advisor` |
| GET | `/api/incidents`, `/api/incidents/{id}` | модуль интерфейса |
| POST | `/api/incidents/{id}/reanalyze` | модуль: разобрать заново |
| POST | `/api/incidents/{id}/feedback` | модуль: «разбор верный / неверный» |
| GET | `/api/evaluation?scenarioId=` | точность на размеченных сбоях |
| GET | `/api/info` | состояние: Zabbix, LLM |
| GET, POST | `/api/settings/retention` | чтение и сохранение сроков хранения через прокси Zabbix администратором |
| POST | `/api/v1/labels` | test-server: разметка сбоя |

Форматы, защита и таблицы БД описаны в [модулях и контрактах](architecture/modules.md).
