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

Без стенда — `docker compose up -d --build`. С локальной LLM:

```bash
ADVISOR_LLM_PROVIDER=openai \
ADVISOR_LLM_MODEL=hf.co/nvidia/NVIDIA-Nemotron-3.5-Bolt-E4B-4GB \
docker compose --profile stand --profile ollama up -d --build
```

Текущий профиль `ollama` в Compose запрашивает NVIDIA GPU. Модель задавайте явно: её имя используется и сервисом, и загрузчиком Ollama. Пример настроек — [`.env.example`](../.env.example), обоснование выбора — [сравнение LLM](llm/llm-comparison.md).

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
