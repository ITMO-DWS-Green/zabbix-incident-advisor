package ru.itmo.advisor.zabbix;

/** Ошибка вызова Zabbix API: транспорт или ответ с полем error. */
public class ZabbixApiException extends RuntimeException {

    private final Integer code;

    public ZabbixApiException(String message, Integer code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Код JSON-RPC ошибки или {@code null}, если ошибка транспорта. */
    public Integer code() {
        return code;
    }
}
