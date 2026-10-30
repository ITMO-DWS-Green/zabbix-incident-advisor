# Открытые вопросы

Записываем вопрос → задаём → пишем ответ с датой и кто ответил → переносим в нужный раздел базы знаний.

## К заказчику

| # | Вопрос | Ответ | Дата, кто |
|---|---|---|---|
| 1 | Делаем работающий сервис или исследуем алгоритмы? | Сервис. Коробочный, подключается к любому проекту и оповещает ответственных | спринт 1, Николаева Надежда |
| 2 | Устраивает ли формат «расширение Zabbix» вместо собственной системы мониторинга? | | 2026-10-27, на согласовании ([#1](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/1)) |
| 3 | Устраивают ли критерии приёмки и пороги точности (70% / 90%, 60 с)? | | [#2](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/2) |
| 4 | Можно ли отправлять логи во внешнюю LLM (с маскированием) или только локальная модель? | | [#5](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/5) |
| 5 | Telegram как мессенджер для оповещений подходит? Нужны ли другие? | | |
| 6 | Обязательно ли ML, или достаточно статистического разбора и сравнения с базовым подходом? | Со слов команды — на наше усмотрение | нужно подтвердить |
| 7 | Какие диаграммы и документы нужны к защите? | | |
| 8 | Какой объём хостов и метрик считать целевым, сколько хранить разборы? | | [#38](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/38) |

## Внутри команды

| # | Вопрос | Решение | Дата |
|---|---|---|---|
| 1 | Как логи test-server попадают в Zabbix? | Предложено [D-005](../architecture/decisions.md), проверяем в [#6](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/6) | |
| 2 | Своё хранилище для инцидентов и разборов? | [D-007](../architecture/decisions.md), реализовано в каркасе | |
| 3 | Оповещение: действие Zabbix или свой бот? | Предложено [D-006](../architecture/decisions.md), делаем в [#17](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/17) | |
| 4 | На чём ML-часть, если до неё дойдём: Java или Python? | Интерфейсы в `domain` позволяют вынести модель в отдельный сервис | обсуждаем в [#3](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/3) |
| 5 | Разбор запускается через `ADVISOR_ANALYSIS_DELAY` = 60 с после начала проблемы, чтобы успели сработать триггеры соседей. С порогом «≤ 60 с до комментария» это не сходится. Снижаем задержку до 20–30 с или меняем порог? | | [#2](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/2), [#3](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/3) |
| 6 | Объединяем проблемы в инцидент ([#40](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/40)) — меняется модель `Incident` в `domain`. Кто и когда вносит изменение? | | [#3](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/3) |
