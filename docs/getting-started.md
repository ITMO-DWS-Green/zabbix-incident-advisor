# Запуск и проверка стенда

Нужны Git, Docker и Docker Compose. Все команды ниже выполняются из корня репозитория, если не указано иное.

## Быстрый старт

```bash
docker compose --profile stand up -d --build
```

После завершения настройки стенда:

| Что | Где |
|---|---|
| Zabbix | http://localhost:8070 — Admin / zabbix → **Monitoring → Incident advisor** |
| Панель сбоев стенда | http://localhost:8090 |
| API сервиса | http://localhost:8095, заголовок `X-Advisor-Token: dev-ui-token` |
| БД сервиса | `localhost:5433`, advisor / advisor |

Чтобы проверить разбор: на панели стенда включите, например, `ERRORS_5XX` на `database`. Через минуту-две в Zabbix откроются проблемы, а в Incident advisor появятся их разборы.

Без стенда — `docker compose up -d --build`.

## LLM для текста разбора

По умолчанию LLM выключена (`ADVISOR_LLM_PROVIDER=stub`): разбор строится шаблоном, и данные не уходят наружу.

Внешняя бесплатная/условно бесплатная нейронка подключается через OpenAI-compatible API. Например, OpenRouter:

```bash
ADVISOR_LLM_PROVIDER=openai \
ADVISOR_LLM_BASE_URL=https://openrouter.ai/api/v1 \
ADVISOR_LLM_MODEL=meta-llama/llama-3.2-3b-instruct:free \
ADVISOR_LLM_API_KEY=<ключ OpenRouter> \
docker compose --profile stand up -d --build
```

Чтобы проверить ключ без запуска стенда, локально выполните:

```bash
ADVISOR_LLM_API_KEY=<ключ OpenRouter> python3 .github/scripts/check_openrouter.py
```

В GitHub добавьте ключ в **Settings → Secrets and variables → Actions → Repository secrets** с именем `OPENROUTER_API_KEY`. После этого можно вручную запустить workflow **CI** с включённым параметром `openrouter_check`: он отправит синтетический инцидент в OpenRouter и проверит, что модель назвала `database` источником.

Или Groq:

```bash
ADVISOR_LLM_PROVIDER=openai \
ADVISOR_LLM_BASE_URL=https://api.groq.com/openai/v1 \
ADVISOR_LLM_MODEL=llama-3.1-8b-instant \
ADVISOR_LLM_API_KEY=<ключ Groq> \
docker compose --profile stand up -d --build
```

Локально, без внешнего API, можно поднять Ollama:

```bash
ADVISOR_LLM_PROVIDER=openai \
ADVISOR_LLM_MODEL=qwen2.5:3b \
docker compose --profile stand --profile ollama up -d --build
```

Профиль `ollama` в Compose запрашивает NVIDIA GPU. Модель задавайте явно: её имя используется и сервисом, и загрузчиком Ollama. Пример настроек — [`.env.example`](../.env.example), обоснование выбора — [сравнение LLM](llm/llm-comparison.md). Перед внешней LLM факты инцидента маскируются и журналируются в `llm_audit`, но провайдера всё равно надо включать осознанно.

## Проверки

Из корня репозитория:

```bash
(cd service && ./gradlew build)       # Java 21; интеграционные тесты требуют Docker
python3 infra/setup/e2e_test.py       # на поднятом Compose с --profile stand
python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v
```

CI проверяет сборку Java и сквозную цепочку по затронутым путям. Python-тесты бота запускаются отдельно при изменениях его скриптов; они не отправляют сообщений и не требуют секретов.

## Сбросить стенд

Команда удаляет контейнеры и тома, включая данные обеих баз:

```bash
docker compose --profile stand --profile ollama down -v
```

[Настройки сервиса](configuration.md) · [API и контракты](architecture/modules.md) · [Разработка](development.md)
