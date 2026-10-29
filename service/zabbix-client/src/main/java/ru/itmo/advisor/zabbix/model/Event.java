package ru.itmo.advisor.zabbix.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Ответ event.get с selectHosts, selectTags и select_acknowledges. */
public record Event(String eventid, int source, int object, String objectid, long clock, int ns, int value,
                    String name, int severity,
                    @JsonProperty("r_eventid") String recoveryEventId,
                    @JsonProperty("cause_eventid") String causeEventId,
                    List<HostRef> hosts, List<Tag> tags, List<Acknowledge> acknowledges) {

    public Event {
        hosts = hosts == null ? List.of() : hosts;
        tags = tags == null ? List.of() : tags;
        acknowledges = acknowledges == null ? List.of() : acknowledges;
    }

    /** Действие с проблемой: комментарий, подтверждение, смена важности. {@code action} — битовая маска. */
    public record Acknowledge(String acknowledgeid, String userid, long clock, String message, int action) {
    }
}
