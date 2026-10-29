package ru.itmo.advisor.zabbix;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.itmo.advisor.zabbix.model.Event;
import ru.itmo.advisor.zabbix.model.Host;
import ru.itmo.advisor.zabbix.model.HistoryRecord;
import ru.itmo.advisor.zabbix.model.Item;
import ru.itmo.advisor.zabbix.model.Problem;
import ru.itmo.advisor.zabbix.model.ProblemUpdate;
import ru.itmo.advisor.zabbix.model.PushResult;
import ru.itmo.advisor.zabbix.model.PushValue;
import ru.itmo.advisor.zabbix.model.Trend;
import ru.itmo.advisor.zabbix.model.Trigger;
import ru.itmo.advisor.zabbix.model.ValueType;

/**
 * Типизированные методы Zabbix API 7.0, которые нужны расширению.
 * Для всего остального — {@link #rpc()} и любой метод из справочника API.
 */
public class ZabbixApi {

    private static final List<String> HOST_REF = List.of("hostid", "host", "name");

    private final ZabbixRpc rpc;

    public ZabbixApi(ZabbixRpc rpc) {
        this.rpc = rpc;
    }

    public static ZabbixApi create(String baseUrl, TokenProvider tokens) {
        return new ZabbixApi(new ZabbixRpc(baseUrl, tokens, Duration.ofSeconds(30)));
    }

    public ZabbixRpc rpc() {
        return rpc;
    }

    /** Версия API. Работает без токена — удобно для проверки доступности. */
    public String version() {
        return rpc.callAnonymous("apiinfo.version", Map.of()).asText();
    }

    // ---------- конфигурация ----------

    /** Все наблюдаемые хосты с тегами и группами. */
    public List<Host> hosts() {
        return rpc.call("host.get", hostParams(), new TypeReference<>() { });
    }

    public List<Host> hostsByName(Collection<String> names) {
        Map<String, Object> p = hostParams();
        p.put("filter", Map.of("host", names));
        return rpc.call("host.get", p, new TypeReference<>() { });
    }

    /** Элементы данных хостов с тегами. */
    public List<Item> items(Collection<String> hostIds) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("output", List.of("itemid", "hostid", "name", "key_", "value_type", "units",
                "lastvalue", "lastclock", "status", "state"));
        p.put("hostids", hostIds);
        p.put("monitored", true);
        p.put("selectTags", "extend");
        p.put("selectHosts", HOST_REF);
        return rpc.call("item.get", p, new TypeReference<>() { });
    }

    /** Триггеры хостов с раскрытыми выражениями. */
    public List<Trigger> triggers(Collection<String> hostIds) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("output", List.of("triggerid", "description", "expression", "priority", "value", "status"));
        p.put("hostids", hostIds);
        p.put("expandExpression", true);
        p.put("selectTags", "extend");
        p.put("selectHosts", HOST_REF);
        return rpc.call("trigger.get", p, new TypeReference<>() { });
    }

    // ---------- данные ----------

    /** История за интервал, по возрастанию времени. В одном вызове — элементы одного типа значения. */
    public List<HistoryRecord> history(ValueType type, Collection<String> itemIds, Instant from, Instant till, int limit) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("output", "extend");
        p.put("history", type.code());
        p.put("itemids", itemIds);
        p.put("time_from", from.getEpochSecond());
        p.put("time_till", till.getEpochSecond());
        p.put("sortfield", "clock");
        p.put("sortorder", "ASC");
        p.put("limit", limit);
        return rpc.call("history.get", p, new TypeReference<>() { });
    }

    /** Часовые тренды: для длинной истории, где сырых значений уже нет. */
    public List<Trend> trends(Collection<String> itemIds, Instant from, Instant till) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("output", "extend");
        p.put("itemids", itemIds);
        p.put("time_from", from.getEpochSecond());
        p.put("time_till", till.getEpochSecond());
        return rpc.call("trend.get", p, new TypeReference<>() { });
    }

    /** Свои значения в элементы Zabbix (траппер или HTTP-агент с приёмом). */
    public PushResult push(List<PushValue> values) {
        return rpc.call("history.push", values, new TypeReference<>() { });
    }

    // ---------- проблемы и события ----------

    /** Открытые проблемы; {@code recent=true} — плюс недавно закрытые. */
    public List<Problem> problems(boolean recent) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("output", "extend");
        p.put("selectTags", "extend");
        p.put("recent", recent);
        p.put("sortfield", List.of("eventid"));
        p.put("sortorder", "DESC");
        return rpc.call("problem.get", p, new TypeReference<>() { });
    }

    /** События с хостами, тегами и историей действий. */
    public List<Event> events(Collection<String> eventIds) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("output", "extend");
        p.put("eventids", eventIds);
        p.put("selectHosts", HOST_REF);
        p.put("selectTags", "extend");
        p.put("select_acknowledges", "extend");
        return rpc.call("event.get", p, new TypeReference<>() { });
    }

    /** Комментарий к проблеме. Zabbix ограничивает длину сообщения, длинный текст обрезается. */
    public void comment(String eventId, String message) {
        acknowledge(eventId, ProblemUpdate.MESSAGE, truncate(message, 2000), null);
    }

    /** Пометить проблему симптомом другой (причина и симптомы, Zabbix 6.4+). */
    public void markSymptom(String eventId, String causeEventId) {
        acknowledge(eventId, ProblemUpdate.RANK_TO_SYMPTOM, null, causeEventId);
    }

    public void acknowledge(String eventId, int action, String message, String causeEventId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("eventids", List.of(eventId));
        p.put("action", action);
        if (message != null) {
            p.put("message", message);
        }
        if (causeEventId != null) {
            p.put("cause_eventid", causeEventId);
        }
        rpc.call("event.acknowledge", p, new TypeReference<Map<String, Object>>() { });
    }

    // ---------- изменение правил ----------

    /** Создать триггер. {@code expression} — в синтаксисе 7.0, например {@code avg(/host/key,5m)>0.1}. */
    public String createTrigger(String description, String expression, int priority, String comments) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("description", description);
        p.put("expression", expression);
        p.put("priority", priority);
        if (comments != null) {
            p.put("comments", comments);
        }
        Map<String, List<String>> result = rpc.call("trigger.create", p, new TypeReference<>() { });
        return result.get("triggerids").get(0);
    }

    /** Изменить поля триггера: {@code expression}, {@code priority}, {@code status} и т. д. */
    public void updateTrigger(String triggerId, Map<String, Object> fields) {
        Map<String, Object> p = new LinkedHashMap<>(fields);
        p.put("triggerid", triggerId);
        rpc.call("trigger.update", p, new TypeReference<Map<String, Object>>() { });
    }

    private static Map<String, Object> hostParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("output", HOST_REF);
        p.put("selectTags", "extend");
        p.put("selectHostGroups", List.of("groupid", "name"));
        p.put("monitored_hosts", true);
        return p;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
