# Функциональные требования

Статус: **черновик**, пересогласование с заказчиком — [#1](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/1). Заменяют 13 требований спринта 1 ([архив](../archive/log-collector-sprint-1/requirements-functional.md)): продукт теперь — расширение Zabbix ([D-004](../architecture/decisions.md)).

Приоритет: **MVP** — обязательно к защите; **Если успеем** — после MVP, по порядку из [дорожной карты](../product/roadmap.md).

## MVP

| ID | Требование | Спринт | Задачи |
|---|---|---|---|
| FR-01 | Система должна обеспечивать получение в реальном времени событий Zabbix о возникновении и закрытии проблем через коннектор. | 2 | ✓ [#47](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/47), [#41](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/41) |
| FR-02 | Система должна обеспечивать получение из Zabbix через API истории метрик и записей логов наблюдаемых сервисов за заданный интервал времени. | 2 | ✓ [#46](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/46), [#6](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/6) |
| FR-03 | Система должна обеспечивать построение карты связей сервисов («кто от кого зависит») по тегам хостов Zabbix. | 2 | ✓ [#48](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/48), [#14](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/14) |
| FR-04 | Система должна обеспечивать объединение проблем, возникших в близком окне времени на связанных сервисах, в один инцидент. | 2 | [#40](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/40) |
| FR-05 | Система должна обеспечивать формирование для инцидента ранжированного списка подозреваемых сервисов по тому, какой сервис раньше других отклонился от своей нормы. | 3 | [#8](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/8), [#9](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/9) |
| FR-06 | Система должна обеспечивать подбор доказательств к инциденту: отклонившихся метрик и характерных записей логов вокруг начала инцидента. | 3 | [#8](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/8), [#10](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/10) |
| FR-07 | Система должна обеспечивать публикацию разбора инцидента комментарием к проблеме в Zabbix с указанием вероятного источника, доказательств и ссылок на графики. | 2–3 | ✓ [#49](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/49), [#11](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/11) |
| FR-08 | Система должна обеспечивать отправку оповещения с разбором в Telegram ответственной команде, определённой по справочнику «сервис — команда». | 2 | [#17](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/17) |
| FR-09 | Система должна обеспечивать приём разметки сбоев от тестового стенда и расчёт точности разбора: доли инцидентов, где источник назван первым и в первых трёх, и времени от начала сбоя до разбора. | 3 | ✓ [#50](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/50), [#15](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/15) |
| FR-10 | Система должна обеспечивать сравнение своего разбора с базовым подходом «источник — сервис, чей триггер сработал первым» на одних и тех же размеченных сбоях. | 3 | [#15](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/15) |
| FR-11 | Система должна предоставлять в интерфейсе Zabbix карточку инцидента: таймлайн отклонений по сервисам, графики метрик, доказательства из логов, список подозреваемых. | 2–4 | ✓ [#52](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/52), [#13](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/13) |
| FR-12 | Система должна предоставлять в интерфейсе Zabbix страницу с результатами оценки точности разбора и сравнения с базовым подходом. | 4 | [#42](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/42) |

✓ — основа сделана в каркасе, задача после ✓ — доработка.

## Если успеем

| ID | Требование | Идея | Задачи |
|---|---|---|---|
| FR-13 | Система должна обеспечивать обнаружение аномального поведения сервиса моделью машинного обучения по группе его метрик и запись оценки аномальности в элемент Zabbix. | 18, 22 | [#26](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/26), [#30](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/30) |
| FR-14 | Система должна обеспечивать краткий пересказ разбора с помощью LLM с маскированием имён, адресов и секретов перед отправкой. | 3, 4, 14 | [#11](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/11), [#12](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/12), [#22](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/22) |
| FR-15 | Система должна обеспечивать отметку в Zabbix проблем-симптомов, чтобы оповещение приходило о причине, а не о каждом следствии. | 8 | [#16](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/16) |
| FR-16 | Система должна обеспечивать поиск похожих прошлых инцидентов и показ того, что тогда помогло. | 10 | [#18](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/18) |
| FR-17 | Система должна предоставлять инженеру оценку разбора («верно» / «неверно, причина в …») и учитывать её в точности. | 13 | [#21](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/21) |

Остальные идеи (11, 12, 15–17, 19–21, 23–25) пока без требований — заводим FR, когда берём в спринт.

## Что изменилось по сравнению со спринтом 1
- **Сбор, хранение, триггеры** больше не делаем сами — это Zabbix (было FR-03, FR-04, FR-05).
- **Генератор** заменён стендом [test-server](https://github.com/ITMO-DWS-Green/test-server) со сбоями по кнопке и разметкой (было FR-01, FR-02).
- **Своё веб-приложение** заменено модулем внутри интерфейса Zabbix (было FR-13).
- **Чат инцидента** — сообщение в групповой чат команды в Telegram: бот Telegram не может сам создавать группы ([D-006](../architecture/decisions.md)) (было FR-11, FR-12).
- **Два подхода** теперь сравниваются в разборе причины: наш разбор против «первого сработавшего триггера». ML — опционально (FR-13) (было FR-06, FR-07).
