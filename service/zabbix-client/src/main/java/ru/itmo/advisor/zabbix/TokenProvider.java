package ru.itmo.advisor.zabbix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Откуда брать токен Zabbix API. */
@FunctionalInterface
public interface TokenProvider {

    String token();

    static TokenProvider fixed(String token) {
        return () -> token;
    }

    /** Токен из файла — его пишет контейнер setup. Читается при каждом вызове, поэтому подхватывает замену. */
    static TokenProvider file(Path path) {
        return () -> {
            try {
                return Files.readString(path).trim();
            } catch (IOException e) {
                throw new ZabbixApiException("Не прочитан токен Zabbix API из " + path, null, e);
            }
        };
    }
}
