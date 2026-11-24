# Тест-план поиска причины сбоя

План для [#4](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/4). Модульные тесты передают данные через порты анализа. Тесты `app` прогоняют сборку инцидентов и разбор через подменённые источник данных, хранилище и запись комментариев.

## Сценарии на фиксированных данных

| Сценарий | Вход | Ожидаемый результат и проверка |
|---|---|---|
| Каскад | `gateway → orders → database`. Задержка `database` растёт первой, затем растёт доля 5xx у `orders` и `gateway`. В логах `database` появляются два вида исключений среди фоновых `INFO`. | Один инцидент и один запланированный разбор в `IncidentServiceTest.cascadeDatabasePlaybookGivesOneIncidentAndOneAnalysis`. `AnalysisServiceTest` проверяет `database` на первом месте, начало отклонения, три отклонившиеся метрики и характерные строки без `INFO`. В варианте с более ранней метрикой `gateway` первое место должно остаться у `database` (обычная проверка в `SuspectRankingScenarioTest`). |
| Одиночный сбой | Задержка одного сервиса `database` устойчиво меняется с 50 до 400 за 90 с до триггера. | `MetricDeviationScenarioTest.findsStartOfSustainedDeviationOnSingleService` проверяет одно отклонение на `database` и точный момент начала скачка. |
| Два независимых сбоя внахлёст | Задержки у `database` и `ledger`. Проблемы зависимых сервисов из двух несвязанных веток поступают вперемешку в пределах одного окна объединения. В отчёт подаются две пересекающиеся разметки. | `IncidentServiceTest.overlappingFaultsInIndependentBranchesAreAnalyzedSeparately` проверяет два отдельных разбора с источниками `database` и `ledger`. `TimeOverlapEvaluatorTest` проверяет, что один разбор засчитывается один раз, а при двух разборах каждый связан со своим сбоем (RED). |
| Нет данных | Проблема зарегистрирована, метрических рядов и логов нет. На вход шаблона поданы пустые списки кандидатов, отклонений и строк логов. | `MetricDeviationScenarioTest.noMetricsGiveNoDeviations` и `PatternLogEvidenceExtractorTest.noLogsGiveNoEvidence` ожидают пустые результаты. `TemplateExplanationWriterTest.templateReportsMissingEvidenceWithoutNamingASource` проверяет сообщение о том, что источник не определён и отклонений метрик нет. |
| Шум без сбоя | Задержка колеблется около 50, одна точка поднимается до 400 и сразу возвращается к обычным значениям. | `MetricDeviationScenarioTest.ignoresAnIsolatedSpikeInNormalNoise` ожидает пустой список отклонений. |

Общие построители в `domain/src/testFixtures` задают фиксированные времена и идентификаторы. Тесты могут задать своё окно, хосты и зависимости, форму метрического ряда, время сбоя, строки логов и разметку. `CascadeFixture` хранит карту, ряды и логи каскада для повторного использования.

## Проверки по модулям

| Модуль и задача | Обычная сборка | Целевые тесты `mvp-target` |
|---|---|---|
| `analysis-metrics` [#8](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/8) | `MetricDeviationScenarioTest` проверяет устойчивое отклонение, пустой вход и игнорирование одиночного скачка на `RobustDeviationDetector`. | Эти сценарии проходят в обычной сборке. |
| `analysis-logs` [#10](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/10) | `PatternLogEvidenceExtractorTest` из [PR #93](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/pull/93) проверяет ошибки в формате test-server, группировку похожих строк, фильтрацию фона и пустой вход. | Поведение закреплено проходящими тестами. |
| `analysis-ranking` [#9](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/9), [#40](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/40) | `GraphWindowMergePolicyTest` проверяет объединение каскада и разделение независимых проблем. `SuspectRankingScenarioTest` проверяет `GraphEvidenceRanker`: `database` первым и объяснение её связи с `orders`, даже если симптом gateway возник на 10 с раньше. | Эти сценарии проходят в обычной сборке. |
| `analysis-explain` [#11](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/11) | `TemplateExplanationWriterTest` проверяет факты каскада в шаблоне и сообщение при отсутствии доказательств. | При выдуманном источнике `unknown-worker` ожидается тот же шаблон на исходных фактах. Сейчас возвращается текст поддельной LLM. |
| `analysis-graph` [#14](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/14) | `TagServiceGraphProviderTest` проверяет направление `gateway → orders → database` и отдельную ветку `billing → ledger` по тегам `depends_on`. | В задаче #14 проверка восстановления связей из логов следует за согласованием входа `ServiceGraphProvider`. |
| `analysis-evaluation` [#15](https://github.com/ITMO-DWS-Green/zabbix-incident-advisor/issues/15) | `TimeOverlapEvaluatorTest` проверяет счётчики первого места и первой тройки, четвёртое место и среднее время до разбора. | Один разобранный инцидент должен засчитываться за один размеченный сбой. Сейчас он даёт два совпадения. При двух инцидентах обе строки по-прежнему ссылаются на первый. |

В чекбоксе #4 о падающем тесте для каждого модуля согласовано уточнение. Для готового извлечения логов и построения графа по тегам используются проходящие проверки. Восстановление графа из логов проверяется в #14 после согласования контракта. Остальные целевые проверки падают на утверждениях о результате и остаются RED до реализации в задачах модулей.

## Запуск

Из `service/` на Windows (на Linux используйте `./gradlew`):

```powershell
.\gradlew.bat build
.\gradlew.bat test -PmvpTargetTests --continue
```

Обычная сборка исключает `mvp-target` и включает интеграционные тесты API на PostgreSQL через Testcontainers. Второй запуск выбирает только `mvp-target`. Ожидаются три падения в двух модулях (`analysis-explain`, `analysis-evaluation`). Отчёты находятся в `build/reports/tests/test/` соответствующих модулей.

## Сквозная проверка на Zabbix

На чистом Compose-проекте из корня репозитория:

```powershell
docker compose --profile stand up -d --build
python infra/setup/e2e_test.py
```

Скрипт включает 5xx на `database` в `test-server`. Он ждёт проблемы от нескольких сервисов в одном инциденте, комментарий к проблеме в Zabbix, исключение `database` в разборе и разметку в отчёте. Дополнительно проверяются страница модуля с прокси и доставка строк `database` и `payments` в историю Zabbix. Точный порядок кандидатов, момент отклонения, отсутствие данных, одиночный скачок и два независимых сбоя проверяются на фиксированных данных.
