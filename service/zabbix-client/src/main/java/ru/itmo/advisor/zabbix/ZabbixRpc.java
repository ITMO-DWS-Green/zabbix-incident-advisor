package ru.itmo.advisor.zabbix;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Низкоуровневый JSON-RPC 2.0 клиент: POST на {@code /api_jsonrpc.php}, токен в {@code Authorization: Bearer}.
 * Типизированные методы — в {@link ZabbixApi}.
 */
public class ZabbixRpc {

    private final HttpClient http;
    private final URI endpoint;
    private final TokenProvider tokens;
    private final ObjectMapper json;
    private final Duration timeout;
    private final AtomicLong ids = new AtomicLong();

    public ZabbixRpc(String baseUrl, TokenProvider tokens, Duration timeout) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/api_jsonrpc.php");
        this.tokens = tokens;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public ObjectMapper json() {
        return json;
    }

    public <T> T call(String method, Object params, TypeReference<T> type) {
        return json.convertValue(call(method, params, true), type);
    }

    /** Вызов без авторизации — для {@code apiinfo.version}. */
    public JsonNode callAnonymous(String method, Object params) {
        return call(method, params, false);
    }

    private JsonNode call(String method, Object params, boolean authorized) {
        ObjectNode body = json.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("method", method)
                .put("id", ids.incrementAndGet());
        body.set("params", json.valueToTree(params));

        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json-rpc")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (authorized) {
            request.header("Authorization", "Bearer " + tokens.token());
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ZabbixApiException(method + ": Zabbix API недоступен: " + e.getMessage(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ZabbixApiException(method + ": прервано", null, e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new ZabbixApiException(method + ": HTTP " + response.statusCode(), null, null);
        }

        JsonNode answer;
        try {
            answer = json.readTree(response.body());
        } catch (IOException e) {
            throw new ZabbixApiException(method + ": ответ не JSON", null, e);
        }
        JsonNode error = answer.get("error");
        if (error != null && !error.isNull()) {
            String data = error.path("data").asText("");
            String message = error.path("message").asText("");
            throw new ZabbixApiException(method + ": " + (data.isEmpty() ? message : data),
                    error.path("code").asInt(), null);
        }
        return answer.get("result");
    }
}
