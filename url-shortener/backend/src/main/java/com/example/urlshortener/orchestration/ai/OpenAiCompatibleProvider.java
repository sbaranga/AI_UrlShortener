package com.example.urlshortener.orchestration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OpenAiCompatibleProvider implements AiProvider {

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    public OpenAiCompatibleProvider(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build();
    }

    @Override
    public void execute(AiTask task) {
        if (!properties.isEnabled()) {
            return;
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException("AI integration is enabled but app.orchestration.ai.api-key is empty");
        }

        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", properties.getModel(),
                    "temperature", 0,
                    "messages", new Object[] {
                        Map.of(
                                "role", "system",
                                "content", "You are a delivery workflow worker. Complete the assigned module and return a concise result."),
                        Map.of(
                                "role", "user",
                                "content", "Run ID: " + task.runId() + "\nModule: " + task.module().displayName()
                                        + "\nAttempt: " + task.attempt()
                                        + "\nProduce the module result for this workflow stage. Do not modify files or deploy anything."),
                    }));
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getEndpoint()))
                    .timeout(properties.getTimeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("AI provider returned HTTP " + response.statusCode());
            }
            JsonNode content = objectMapper.readTree(response.body()).at("/choices/0/message/content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new IllegalStateException("AI provider returned no message content");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("AI provider request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI provider request interrupted", exception);
        }
    }
}
