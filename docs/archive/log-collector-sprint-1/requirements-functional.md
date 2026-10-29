> Архив: версия из log-collector на конец спринта 1 (своя система мониторинга вместо расширения Zabbix). Ссылки внутри ведут на старую структуру. Актуальное — в [docs/](../../README.md).

# Функциональные требования

Статус: **черновик, на согласовании с заказчиком** ([задача #1](https://github.com/ITMO-DWS-Green/log-collector/issues/1)). Источник — документ требований в Google Docs (ведёт Михаил Катан).

| ID | Требование | Задачи |
|---|---|---|
| FR-01 | Система должна обеспечивать генерацию синтетических логов и метрик нескольких взаимодействующих сервисов, имитирующих работу облачной инфраструктуры, либо использовать внешнее API для получения логов. | [#7](https://github.com/ITMO-DWS-Green/log-collector/issues/7) [#8](https://github.com/ITMO-DWS-Green/log-collector/issues/8) [#9](https://github.com/ITMO-DWS-Green/log-collector/issues/9) [#10](https://github.com/ITMO-DWS-Green/log-collector/issues/10) |
| FR-02 | Система должна обеспечивать внедрение сбоев различных типов по заданному сценарию и сохранение разметки каждого инцидента: времени начала и окончания, типа и сервиса-источника. | [#11](https://github.com/ITMO-DWS-Green/log-collector/issues/11) [#12](https://github.com/ITMO-DWS-Green/log-collector/issues/12) [#24](https://github.com/ITMO-DWS-Green/log-collector/issues/24) |
| FR-03 | Система должна обеспечивать приём логов и метрик в потоковом режиме, а также загрузку данных из файла для офлайн-анализа. | [#14](https://github.com/ITMO-DWS-Green/log-collector/issues/14) [#15](https://github.com/ITMO-DWS-Green/log-collector/issues/15) |
| FR-04 | Система должна обеспечивать приведение логов и метрик к единому формату и их привязку к общей временной шкале. | [#13](https://github.com/ITMO-DWS-Green/log-collector/issues/13) [#16](https://github.com/ITMO-DWS-Green/log-collector/issues/16) |
| FR-05 | Система должна обеспечивать обнаружение аномалий на основе правил и статистических порогов. | [#18](https://github.com/ITMO-DWS-Green/log-collector/issues/18) [#19](https://github.com/ITMO-DWS-Green/log-collector/issues/19) |
| FR-06 | Система должна обеспечивать обнаружение аномалий с помощью модели машинного обучения на тех же данных. | [#20](https://github.com/ITMO-DWS-Green/log-collector/issues/20) [#21](https://github.com/ITMO-DWS-Green/log-collector/issues/21) |
| FR-07 | Система должна обеспечивать сравнение двух подходов на размеченных сценариях по показателям precision, recall, F1, доле ложных тревог и задержке обнаружения. | [#25](https://github.com/ITMO-DWS-Green/log-collector/issues/25) [#26](https://github.com/ITMO-DWS-Green/log-collector/issues/26) |
| FR-08 | Система должна обеспечивать объединение связанных срабатываний в единый инцидент для исключения дублирующих оповещений. | [#22](https://github.com/ITMO-DWS-Green/log-collector/issues/22) [#23](https://github.com/ITMO-DWS-Green/log-collector/issues/23) |
| FR-09 | Система должна обеспечивать формирование ранжированного списка подозрительных сервисов, связанных с инцидентом. | [#27](https://github.com/ITMO-DWS-Green/log-collector/issues/27) |
| FR-10 | Система должна обеспечивать объяснение инцидента с указанием отклонившихся метрик и примеров характерных записей логов. | [#28](https://github.com/ITMO-DWS-Green/log-collector/issues/28) |
| FR-11 | Система должна обеспечивать определение ответственной команды по сервису-источнику инцидента на основе справочника «сервис — команда». | [#29](https://github.com/ITMO-DWS-Green/log-collector/issues/29) [#30](https://github.com/ITMO-DWS-Green/log-collector/issues/30) |
| FR-12 | Система должна обеспечивать автоматическое создание чата инцидента в мессенджере, добавление в него ответственных участников и отправку описания инцидента. | [#31](https://github.com/ITMO-DWS-Green/log-collector/issues/31) [#32](https://github.com/ITMO-DWS-Green/log-collector/issues/32) |
| FR-13 | Система должна предоставлять веб-интерфейс для просмотра списка инцидентов, карточки инцидента с графиками метрик и логами, а также результатов сравнения подходов. | [#33](https://github.com/ITMO-DWS-Green/log-collector/issues/33) [#34](https://github.com/ITMO-DWS-Green/log-collector/issues/34) [#35](https://github.com/ITMO-DWS-Green/log-collector/issues/35) |

## Замечания к формулировкам
- **FR-01:** «синтетических» и «имитирующих работу» читаются как «песочница», а «внешнее API» — как работа с реальными логами. Нужно зафиксировать, что основной режим — генератор, а внешний API — опция ([вопрос](open-questions.md)).
- **FR-02:** разметку сохраняем для каждого внедрённого *сбоя*; «инцидент» в нашем [едином языке](../product/glossary.md) — это результат обнаружения.
- **FR-12:** бот Telegram не может сам создавать группы — способ создания чата нужно выбрать заранее ([#31](https://github.com/ITMO-DWS-Green/log-collector/issues/31)).
