package com.research.summarizer.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.summarizer.model.SearchResult;
import com.research.summarizer.model.Source;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Agent 1: decides where to search and returns raw sources.
 * OpenAI developer topics go to the OpenAI Docs MCP server first, with web search as fallback.
 * Everything else goes straight to web search (Tavily).
 */
@Component
public class SearchAgent {

    private static final Logger log = LoggerFactory.getLogger(SearchAgent.class);

    /**
     * OpenAI products and APIs. One match is enough. Written without spaces or hyphens because the
     * topic is squashed the same way, so "Open AI", "open-ai" and "OpenAI" all match "openai".
     * Deliberately excluded: "gpt" alone, "ai", "llm", "agents", "tokens", "prompt", "api key",
     * "playground" - too generic, they would send most AI questions to the OpenAI docs.
     */
    static final List<String> OPENAI_TERMS = List.of(
            "openai", "chatgpt", "gptoss", "gptimage", "gptrealtime", "dalle", "whisper", "sora", "codex",
            "responsesapi", "responseapi", "chatcompletion", "completionsapi", "assistantsapi", "assistantapi",
            "realtimeapi", "agentssdk", "agentsdk", "agentkit", "agentbuilder", "chatkit", "appssdk",
            "codeinterpreter", "textembedding", "omnimoderation", "customgpts", "gptstore");

    /**
     * Model names follow a pattern, so match the pattern instead of listing every model:
     * gpt-4o, gpt4, gpt 5, gpt-4.1, o1, o3-mini, o4-mini, and future ones like gpt-6 or o5.
     */
    static final Pattern MODEL_NAME = Pattern.compile("\\b(gpt[- ]?\\d|o[1-9]\\b)");

    /**
     * Concepts the assignment names as OpenAI query types. One is enough, but only when no other vendor
     * is named, so "How does fine-tuning work?" goes to MCP and "fine-tuning Llama with LoRA" goes to the web.
     */
    static final List<String> PRIMARY_CONCEPT_TERMS = List.of("finetun");

    /**
     * LLM concepts OpenAI documents but does not own. Two are needed, and only when no other vendor
     * is named, so "embeddings with Cohere" goes to the web.
     */
    static final List<String> CONCEPT_TERMS = List.of(
            "embedding", "functioncalling", "toolcalling", "structuredoutput", "jsonmode",
            "promptcaching", "reasoningeffort", "moderation", "vectorstore", "filesearch", "batchapi",
            "distillation");

    /** Other vendors and frameworks. Whole words, so "coherent" does not match "cohere". */
    static final Pattern COMPETITOR = Pattern.compile(
            "\\b(anthropic|claude|gemini|llama|mistral|hugging ?face|cohere|deepseek|qwen"
                    + "|langchain|langgraph|llamaindex|crewai|autogen)\\b");

    /** Words ignored when building the search query and checking relevance. */
    static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "in", "on", "to", "is", "are", "for", "with", "how", "what",
            "why", "when", "does", "do", "can", "use", "using", "work", "works", "about", "between", "vs");

    private static final int MAX_CHARS_PER_SOURCE = 6000;
    private static final int MIN_CONTENT_CHARS = 100;

    private final McpClient mcp;
    private final RestClient tavily;
    private final ObjectMapper mapper = new ObjectMapper();

    public SearchAgent(@Lazy McpClient mcp, @Value("${tavily.api-key}") String tavilyKey) {
        this.mcp = mcp;
        this.tavily = RestClient.builder()
                .baseUrl("https://api.tavily.com/search")
                .defaultHeader("Authorization", "Bearer " + tavilyKey)
                .build();
    }

    public SearchResult search(String topic, int maxSources) {
        if (isOpenAiRelated(topic)) {
            try {
                List<Source> docs = searchOpenAiDocs(topic, maxSources);
                if (docs.size() >= Math.min(2, maxSources)) {
                    log.info("SearchAgent: routed to MCP, {} docs", docs.size());
                    return new SearchResult("MCP", docs);
                }
                log.info("SearchAgent: MCP returned only {} relevant docs, falling back to web", docs.size());
            } catch (Exception e) {
                log.warn("SearchAgent: MCP failed ({}), falling back to web", e.getMessage());
            }
        }
        List<Source> docs = searchWeb(topic, maxSources);
        log.info("SearchAgent: routed to WEB, {} docs", docs.size());
        if (docs.isEmpty()) {
            throw new IllegalStateException("No sources found for topic: " + topic);
        }
        return new SearchResult("WEB", docs);
    }

    /**
     * Routing rule, deterministic:
     * 1. An OpenAI product/API name or model name: yes.
     * 2. Otherwise, another vendor named: no.
     * 3. Otherwise, a primary concept (fine-tuning) or two or more concept terms: yes.
     * 4. Otherwise: no.
     * A wrong "yes" is harmless, because the MCP relevance check falls back to the web.
     */
    static boolean isOpenAiRelated(String topic) {
        if (topic == null || topic.isBlank()) {
            return false;
        }
        String text = topic.toLowerCase();
        String squashed = text.replaceAll("[\\s_-]+", ""); // "Fine-Tuning" -> "finetuning"

        if (MODEL_NAME.matcher(text).find() || OPENAI_TERMS.stream().anyMatch(squashed::contains)) {
            return true;
        }
        if (COMPETITOR.matcher(text).find()) {
            return false;
        }
        if (PRIMARY_CONCEPT_TERMS.stream().anyMatch(squashed::contains)) {
            return true;
        }
        return CONCEPT_TERMS.stream().filter(squashed::contains).count() >= 2;
    }

    /** MCP: search the docs, keep relevant pages, then fetch each page's content. */
    private List<Source> searchOpenAiDocs(String topic, int maxSources) throws Exception {
        String result = callMcpTool("search_openai_docs", Map.of("query", searchQuery(topic), "limit", 20));
        List<Source> docs = new ArrayList<>();
        Set<String> seenPages = new LinkedHashSet<>();
        for (JsonNode hit : mapper.readTree(result).path("hits")) {
            String page = hit.path("url_without_anchor").asText();
            String title = hit.path("hierarchy").path("lvl1").asText();
            if (docs.size() >= maxSources || !isRelevant(topic, title + " " + page) || !seenPages.add(page)) {
                continue;
            }
            String content = callMcpTool("fetch_openai_doc", Map.of("url", page));
            if (content.length() > MIN_CONTENT_CHARS) {
                docs.add(new Source("S" + (docs.size() + 1), title, page, truncate(content)));
            }
        }
        return docs;
    }

    private String callMcpTool(String tool, Map<String, Object> args) throws Exception {
        var request = ToolExecutionRequest.builder()
                .name(tool)
                .arguments(mapper.writeValueAsString(args))
                .build();
        return mcp.executeTool(request).resultText();
    }

    /** Web: Tavily search, using its relevant snippet plus the page text. */
    private List<Source> searchWeb(String topic, int maxSources) {
        Map<String, Object> body = Map.of("query", topic, "max_results", maxSources,
                "search_depth", "advanced", "include_raw_content", "text");
        JsonNode response = tavily.post()
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        List<Source> docs = new ArrayList<>();
        if (response == null) {
            return docs;
        }
        for (JsonNode r : response.path("results")) {
            String content = (r.path("content").asText() + "\n\n" + r.path("raw_content").asText("")).strip();
            if (content.length() > MIN_CONTENT_CHARS) { // same minimum as the MCP path
                docs.add(new Source("S" + (docs.size() + 1), r.path("title").asText(), r.path("url").asText(), truncate(content)));
            }
        }
        return docs;
    }

    /**
     * The docs search is fuzzy and always returns something ("best pizza in naples" returns
     * "Best practices for ChatGPT Edu"), so a hit only counts if it contains at least
     * two-thirds of the topic's keywords.
     */
    static boolean isRelevant(String topic, String hitText) {
        List<String> keywords = keywords(topic);
        if (keywords.isEmpty()) {
            return true;
        }
        String text = hitText.toLowerCase();
        long matched = keywords.stream().filter(text::contains).count();
        return matched * 3 >= keywords.size() * 2L;
    }

    /** Topic words without stopwords and without words found in every OpenAI docs page. */
    static List<String> keywords(String topic) {
        return words(topic).stream()
                .filter(w -> w.length() >= 3 && !List.of("openai", "api", "sdk").contains(w))
                .toList();
    }

    /** "How does streaming work in the OpenAI Responses API?" becomes "streaming openai responses api". */
    static String searchQuery(String topic) {
        return String.join(" ", words(topic));
    }

    private static List<String> words(String topic) {
        List<String> words = new ArrayList<>();
        for (String w : topic.toLowerCase().split("[^a-z0-9.\\-]+")) {
            w = w.replaceAll("^[.\\-]+|[.\\-]+$", "");
            if (!w.isEmpty() && !STOPWORDS.contains(w)) {
                words.add(w);
            }
        }
        return words;
    }

    private static String truncate(String s) {
        return s.length() <= MAX_CHARS_PER_SOURCE ? s : s.substring(0, MAX_CHARS_PER_SOURCE);
    }
}
