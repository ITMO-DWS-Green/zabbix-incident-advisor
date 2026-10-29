package ru.itmo.advisor.analysis.stream;

import java.util.List;
import ru.itmo.advisor.domain.model.ItemValue;
import ru.itmo.advisor.domain.port.ValueStreamProcessor;

/**
 * Идеи 11, 18, 22, заглушка: значения из коннектора пока не обрабатываются.
 *
 * <p>TODO идея 11: шаблоны строк логов → новые сигнатуры → {@code ZabbixWriter.push} в элемент-траппер.
 */
public class NoopValueStreamProcessor implements ValueStreamProcessor {

    @Override
    public void onValues(List<ItemValue> values) {
        // заглушка
    }
}
