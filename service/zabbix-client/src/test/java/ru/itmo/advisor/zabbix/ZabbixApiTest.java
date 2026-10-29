package ru.itmo.advisor.zabbix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.zabbix.model.ValueType;

/** Проверяет JSON-RPC формат запросов и разбор ответов на поддельном Zabbix. */
class ZabbixApiTest {

    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    private HttpServer server;

    private ZabbixApi start(Function<JsonNode, String> responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api_jsonrpc.php", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            requests.add(request);
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = responder.apply(request).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return ZabbixApi.create("http://127.0.0.1:" + server.getAddress().getPort(), TokenProvider.fixed("secret"));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void historyGetSendsJsonRpcWithBearerTokenAndParsesStringNumbers() throws IOException {
        ZabbixApi api = start(r -> """
                {"jsonrpc":"2.0","id":1,"result":[
                  {"itemid":"45678","clock":"1761382800","ns":"1","value":"0.25"}]}""");

        var records = api.history(ValueType.FLOAT, List.of("45678"),
                Instant.ofEpochSecond(1761382000), Instant.ofEpochSecond(1761383000), 1000);

        JsonNode request = requests.get(0);
        assertThat(request.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(request.get("method").asText()).isEqualTo("history.get");
        assertThat(request.at("/params/history").asInt()).isZero();
        assertThat(request.at("/params/time_from").asLong()).isEqualTo(1761382000);
        assertThat(authHeaders.get(0)).isEqualTo("Bearer secret");
        assertThat(records).singleElement().satisfies(rec -> {
            assertThat(rec.clock()).isEqualTo(1761382800L);
            assertThat(rec.asDouble()).isEqualTo(0.25);
        });
    }

    @Test
    void apiErrorBecomesException() throws IOException {
        ZabbixApi api = start(r -> """
                {"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Invalid params.","data":"No permissions."}}""");

        assertThatThrownBy(() -> api.hosts())
                .isInstanceOf(ZabbixApiException.class)
                .hasMessageContaining("host.get")
                .hasMessageContaining("No permissions.");
    }

    @Test
    void versionIsAnonymous() throws IOException {
        ZabbixApi api = start(r -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"7.0.21\"}");

        assertThat(api.version()).isEqualTo("7.0.21");
        assertThat(authHeaders.get(0)).isNull();
    }

    @Test
    void markSymptomSendsRankBitAndCause() throws IOException {
        ZabbixApi api = start(r -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"eventids\":[\"42\"]}}");

        api.markSymptom("42", "41");

        JsonNode params = requests.get(0).get("params");
        assertThat(params.get("action").asInt()).isEqualTo(0x100);
        assertThat(params.get("cause_eventid").asText()).isEqualTo("41");
    }
}
