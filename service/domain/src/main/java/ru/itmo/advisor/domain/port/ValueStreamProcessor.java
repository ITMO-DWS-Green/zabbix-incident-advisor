package ru.itmo.advisor.domain.port;

import java.util.List;
import ru.itmo.advisor.domain.model.ItemValue;

/**
 * Идеи 11, 18, 22. Получает поток значений из коннектора в реальном времени
 * (например, чтобы находить новые типы ошибок в логах или считать норму метрик).
 * Свои результаты можно вернуть в Zabbix через {@link ZabbixWriter#push}. Модуль analysis-stream.
 * Вызывается из потока приёма — долгую работу выносить в свой executor.
 */
public interface ValueStreamProcessor {

    void onValues(List<ItemValue> values);
}
