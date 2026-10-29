package ru.itmo.advisor.zabbix.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Ответ problem.get: открытые и недавно закрытые проблемы. */
public record Problem(String eventid, String objectid, long clock, int ns, String name, int severity,
                      @JsonProperty("r_eventid") String recoveryEventId,
                      @JsonProperty("cause_eventid") String causeEventId,
                      int acknowledged, List<Tag> tags) {

    public Problem {
        tags = tags == null ? List.of() : tags;
    }
}
