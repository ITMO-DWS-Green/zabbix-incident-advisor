package ru.itmo.advisor.zabbix.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Запись trend.get: агрегат за час. */
public record Trend(String itemid, long clock, int num,
                    @JsonProperty("value_min") double min,
                    @JsonProperty("value_avg") double avg,
                    @JsonProperty("value_max") double max) {
}
