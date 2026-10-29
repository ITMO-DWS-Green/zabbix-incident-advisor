# Дорожная карта

Всего 4 спринта, зачётный — в январе. Спринт 1 позади (своя система мониторинга, см. [архив](../archive/log-collector-sprint-1/README.md)), на расширение остаются три. Задачи — в [Issues](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues), спринты — milestones. Идеи взяты из документа «Расширение Zabbix · 25 идей по приоритету» (26.10.2026); номер идеи — её место в очереди.

**Сценарий защиты:** включили сбой на test-server → сработал триггер в Zabbix → расширение назвало источник и доказало это данными → разбор пришёл в Telegram → показали точность на десятках прогонов.

| Спринт | Срок | Фокус |
|---|---|---|
| 1 | 01.10–23.10 | Команда, требования, аналоги, инфраструктура — сделан в log-collector |
| 2 | до 27.11 | Подготовка и сквозной путь |
| 3 | до 18.12 | Настоящий разбор и точность |
| 4 | до 22.01 | Карточка, отчёт о точности, демо, защита |

```mermaid
gantt
  dateFormat YYYY-MM-DD
  axisFormat %d.%m
  section Спринт 2
  Подготовка и сквозной путь :s2, 2026-10-27, 2026-11-27
  section Спринт 3
  Разбор и точность :s3, 2026-11-28, 2026-12-18
  section Спринт 4
  Карточка, отчёт, демо :s4, 2026-12-19, 2027-01-22
```

Приоритеты: **P1** — без этого спринт не закрыть, **P2** — важно, делаем после P1, **P3** — можно сдвинуть в следующий спринт. Порядок в таблицах — порядок, в котором берём задачи; он же записан в описании milestone.

## Спринт 2 · до 27.11 · подготовка и сквозной путь
Цель: требования и архитектура согласованы, проблема в Zabbix доходит до Telegram через наш сервис, пусть пока с простым разбором.

Сделано в каркасе: стенд одной командой, клиент API, приём коннектора, карта связей по тегам, базовый разбор и комментарий в Zabbix, БД и приём разметки, модуль интерфейса, карточка с таймлайном ([#44](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/44)–[#52](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/52)).

| # | Задача | Приоритет | Что |
|---|---|---|---|
| 1 | [#1](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/1) | P1 | Пересогласовать требования с заказчиком |
| 2 | [#3](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/3) | P1 | Ревью архитектуры каркаса всей командой |
| 3 | [#6](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/6) | P1 | Логи test-server в Zabbix |
| 4 | [#2](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/2) | P1 | Критерии приёмки и метрики успеха |
| 5 | [#4](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/4) | P1 | Тест-план для TDD |
| 6 | [#17](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/17) | P2 | Оповещение с разбором в Telegram (FR-08, идея 9) |
| 7 | [#40](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/40) | P2 | Объединение проблем в инцидент (FR-04) |
| 8 | [#5](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/5) | P2 | Выбор LLM и правила работы с данными |
| 9 | [#38](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/38) | P3 | Нефункциональные требования |
| 10 | [#41](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/41) | P3 | Догон пропущенных событий |
| 11 | [#39](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/39) | P3 | test-server отправляет в сервис только разметку |

## Спринт 3 · до 18.12 · настоящий разбор
| # | Задача | Приоритет | Что |
|---|---|---|---|
| 1 | [#8](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/8) | P1 | Отклонения метрик (FR-05, идея 1) |
| 2 | [#9](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/9) | P1 | Ранжирование подозреваемых с учётом карты связей (FR-05, идея 1) |
| 3 | [#15](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/15) | P1 | Точность и сравнение с базовым подходом (FR-09, FR-10, идея 7) |
| 4 | [#10](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/10) | P1 | Доказательства из логов (FR-06, идея 2) |
| 5 | [#11](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/11) | P2 | Объяснение и полный разбор в комментарии (FR-07, идея 3) |
| 6 | [#12](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/12) | P3 | Маскирование и журнал LLM (идея 4) |

## Спринт 4 · до 22.01 · интерфейс и защита
| # | Задача | Приоритет | Что |
|---|---|---|---|
| 1 | [#42](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/42) | P1 | Страница точности и прогоны всех плейбуков (FR-12) |
| 2 | [#7](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/7) | P1 | Демо и презентация |
| 3 | [#43](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/43) | P1 | README и документация к защите |
| 4 | [#13](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/13) | P2 | Карточка: графики, ссылки, логи (FR-11, идея 5) |
| 5 | [#14](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/14) | P3 | Карта связей не только из тегов (идея 6) |

## Если успеем — по порядку (метка `очередь 2`)
| Идея | Задача | Требование |
|---|---|---|
| 8. Причина и симптомы | [#16](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/16) | FR-15 |
| 13. Оценка разбора инженером | [#21](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/21) | FR-17 |
| 10. Похожие прошлые инциденты | [#18](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/18) | FR-16 |
| 14. Выбор LLM, включая локальную | [#22](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/22) | FR-14 |
| 11. Новые типы ошибок в логах | [#19](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/19) | — |
| 12. Шумные триггеры | [#20](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/20) | — |
| 15. Черновик пост-мортема | [#23](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/23) | — |
| 16. Покрытие мониторинга | [#24](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/24) | — |

## После защиты
- Метка `очередь 3` — идеи 17–25 ([#25](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/25)–[#33](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/33)): тюнинг порогов, норма метрики и ML по группе метрик (FR-13), оценка влияния, группировка, новые триггеры, сводка за смену, подавление ожидаемых отклонений, виджет.
- Метка `эксплуатация` — [#34](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/34)–[#37](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/37): установка в чужой Zabbix.
