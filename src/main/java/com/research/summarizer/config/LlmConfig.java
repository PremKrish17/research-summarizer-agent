package com.research.summarizer.config;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configuration
public class LlmConfig {

    /** OpenRouter speaks the OpenAI API format, so LangChain4j's OpenAI client is pointed at it. */
    @Bean
    public ChatModel chatModel(@Value("${openrouter.base-url}") String baseUrl,
                               @Value("${openrouter.api-key}") String apiKey,
                               @Value("${openrouter.model}") String model,
                               @Value("${openrouter.fallback-models}") List<String> fallbackModels) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("OPENROUTER_API_KEY is not set (see .env.example)");
        }
        List<String> models = new ArrayList<>();
        models.add(model);
        models.addAll(fallbackModels);
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                // OpenRouter tries this list in order, moving on only if a model fails.
                .customParameters(Map.of("models", models))
                .temperature(0.0)
                .timeout(Duration.ofSeconds(180))
                .build();
    }

    /** MCP client for the OpenAI Docs server over Streamable HTTP. @Lazy: connects on first use. */
    @Bean
    @Lazy
    public McpClient openAiDocsMcpClient(@Value("${mcp.url}") String url) {
        return new DefaultMcpClient.Builder()
                .transport(new StreamableHttpMcpTransport.Builder()
                        .url(url)
                        .timeout(Duration.ofSeconds(30))
                        .build())
                .build();
    }
}
