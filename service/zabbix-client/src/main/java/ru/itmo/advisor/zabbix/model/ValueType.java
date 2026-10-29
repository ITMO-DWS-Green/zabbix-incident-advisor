package ru.itmo.advisor.zabbix.model;

/** Тип значения элемента: {@code value_type} в API, он же параметр {@code history} в history.get. */
public enum ValueType {
    FLOAT(0), CHAR(1), LOG(2), UNSIGNED(3), TEXT(4);

    private final int code;

    ValueType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean numeric() {
        return this == FLOAT || this == UNSIGNED;
    }

    public static ValueType of(int code) {
        for (ValueType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("Неизвестный value_type " + code);
    }
}
