package ru.itmo.advisor.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import ru.itmo.advisor.domain.port.LlmClient;

/**
 * Клиент к API вида OpenAI Chat Completions: {@code POST {baseUrl}/chat/completions}.
 * Подходит для OpenAI, Ollama ({@code http://ollama:11434/v1}), vLLM, LM Studio и других совместимых серверов.
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public OpenAiCompatibleLlmClient(String baseUrl, String apiKey, String model, Duration timeout) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
    }

    @Override
    public Response complete(Request request) {
        ObjectNode body = json.createObjectNode().put("model", model).put("temperature", request.temperature());
        ArrayNode messages = body.putArray("messages");
        request.messages().forEach(m -> messages.addObject().put("role", m.role()).put("content", m.content()));

        HttpRequest.Builder http = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (apiKey != null && !apiKey.isBlank()) {
            http.header("Authorization", "Bearer " + apiKey);
        }
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = this.http.send(http.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LlmException("LLM ответила HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode answer = json.readTree(response.body());
            String text = answer.at("/choices/0/message/content").asText("");
            return new Response(text, answer.path("model").asText(model), (System.nanoTime() - start) / 1_000_000);
        } catch (IOException e) {
            throw new LlmException("LLM недоступна: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Вызов LLM прерван", e);
        }
    }

    @Override
    public boolean enabled() {
        return true;
    }
}
