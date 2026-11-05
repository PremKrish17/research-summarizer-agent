package com.research.summarizer.agent;

import com.research.summarizer.model.Insight;
import com.research.summarizer.model.SearchResult;
import com.research.summarizer.model.Source;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent 2: extracts key facts, statistics, definitions and quotes from the raw sources.
 * Each insight must carry a verbatim quote; insights whose quote is not in the cited source are dropped.
 */
@Component
public class InsightExtractorAgent {

    private static final Logger log = LoggerFactory.getLogger(InsightExtractorAgent.class);

    /** LangChain4j turns this interface into an LLM call and parses the JSON reply into Insights. */
    interface Extractor {
        @SystemMessage(fromResource = "/prompts/insight-extractor.txt")
        @UserMessage("TOPIC: {{topic}}\n\nSOURCES:\n{{sources}}")
        Insights extract(@V("topic") String topic, @V("sources") String sources);
    }

    record Insights(List<Insight> insights) {
    }

    private final Extractor extractor;

    public InsightExtractorAgent(ChatModel chatModel) {
        this.extractor = AiServices.create(Extractor.class, chatModel);
    }

    public List<Insight> extract(String topic, SearchResult search) {
        String sourcesText = search.sources().stream()
                .map(s -> "[" + s.id() + "] " + s.title() + "\nURL: " + s.url() + "\n" + s.content())
                .collect(Collectors.joining("\n\n"));

        // Free models occasionally return broken JSON or nothing usable, so we ask twice at most.
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                List<Insight> candidates = extractor.extract(topic, sourcesText).insights();
                List<Insight> grounded = candidates == null ? List.of() : keepGrounded(candidates, search.sources());
                log.info("InsightExtractorAgent: attempt {} -> {} insights, {} grounded",
                        attempt, candidates == null ? 0 : candidates.size(), grounded.size());
                if (!grounded.isEmpty()) {
                    return grounded;
                }
            } catch (RuntimeException e) {
                log.warn("InsightExtractorAgent: attempt {} failed: {}", attempt, e.getMessage());
            }
        }
        throw new IllegalStateException("No grounded insights could be extracted for topic: " + topic);
    }

    /** Hallucination check: keep an insight only if its quote really appears in its cited source. */
    static List<Insight> keepGrounded(List<Insight> insights, List<Source> sources) {
        Map<String, Source> byId = sources.stream().collect(Collectors.toMap(Source::id, Function.identity()));
        return insights.stream()
                .filter(i -> i.quote() != null && i.sourceId() != null && byId.containsKey(i.sourceId()))
                .filter(i -> normalize(byId.get(i.sourceId()).content()).contains(normalize(i.quote())))
                .toList();
    }

    /** Lowercase, drop markdown/punctuation, collapse spaces, so formatting differences don't matter. */
    static String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
