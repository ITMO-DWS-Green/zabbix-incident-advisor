# Zabbix Incident Advisor

Расширение Zabbix 7.0 для разбора инцидентов: объединяет связанные проблемы, анализирует историю метрик и помогает инженеру определить вероятный источник сбоя. Результат доступен в интерфейсе Zabbix и в комментариях к проблемам.

В репозитории есть Java-сервис, модуль интерфейса Zabbix и окружение для проверки на стенде. Сквозной сценарий реализован; алгоритмы анализа пока включают базовые реализации и заглушки. Извлечение доказательств из логов и другие возможности развиваются по дорожной карте.

## Документация

| Раздел | Что внутри |
|---|---|
| [База знаний](docs/README.md) | Полная навигация по документации |
| [Описание продукта](docs/product/overview.md) | Назначение, пользователи и границы проекта |
| [Запуск и проверка](docs/getting-started.md) | Стенд, локальная LLM, тесты и сброс окружения |
| [Настройки и API](docs/configuration.md) | Переменные окружения и HTTP-методы |
| [Архитектура](docs/architecture/overview.md) | Компоненты и поток данных |
| [Модули и контракты](docs/architecture/modules.md) | Интерфейсы и состояние модулей |
| [Разработка](docs/development.md) | Модель данных, правила зависимостей и тестирование |
| [Требования и приёмка](docs/requirements/acceptance.md) | Критерии готовности MVP |
| [Дорожная карта](docs/product/roadmap.md) | Спринты и приоритеты |
| [Как работаем](docs/process/workflow.md) | Задачи, ветки и ревью |
| [Telegram-бот](docs/process/telegram-bot.md) | Оповещения, встречи и протоколы |

## Репозиторий

| Раздел | Содержание |
|---|---|
| [service/](service/) | Java-сервис, алгоритмы, интеграции и тесты |
| [zabbix-module/](zabbix-module/) | Модуль интерфейса Zabbix |
| [infra/setup/](infra/setup/) | Настройка стенда и сквозная проверка |
| [compose.yaml](compose.yaml) | Окружение для разработки |

[Задачи](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues) · [Спринты](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/milestones) · [Pull requests](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/pulls) · [CI](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/actions) · [Тестовый стенд](https://github.com/ITMO-DWS-Green/test-server)
