package com.research.summarizer.agent;

import com.research.summarizer.model.Insight;
import com.research.summarizer.model.SearchResult;
import com.research.summarizer.model.SummarizeResponse;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 3: writes the final report (Executive Summary, Key Findings, Details) from the insights.
 * The Sources list is built in code from the search results, so the LLM can never invent a URL.
 */
@Component
public class ReportGeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(ReportGeneratorAgent.class);

    interface Writer {
        @SystemMessage(fromResource = "/prompts/report-generator.txt")
        @UserMessage("TOPIC: {{topic}}\n\nINSIGHTS:\n{{insights}}")
        Report write(@V("topic") String topic, @V("insights") String insights);
    }

    record Report(String executiveSummary, List<String> keyFindings, String details) {
    }

    private final Writer writer;

    public ReportGeneratorAgent(ChatModel chatModel) {
        this.writer = AiServices.create(Writer.class, chatModel);
    }

    public SummarizeResponse generate(String topic, SearchResult search, List<Insight> insights) {
        String insightsText = insights.stream()
                .map(i -> "- [" + i.sourceId() + "] (" + i.type() + ") " + i.statement())
                .collect(Collectors.joining("\n"));

        Report report;
        try {
            report = writer.write(topic, insightsText);
        } catch (RuntimeException e) {
            log.warn("ReportGeneratorAgent: first attempt failed ({}), retrying", e.getMessage());
            report = writer.write(topic, insightsText);
        }
        log.info("ReportGeneratorAgent: report written with {} key findings",
                report.keyFindings() == null ? 0 : report.keyFindings().size());

        // Only list sources that at least one insight actually came from.
        List<String> sources = search.sources().stream()
                .filter(s -> insights.stream().anyMatch(i -> i.sourceId().equals(s.id())))
                .map(s -> "[" + s.id() + "] " + s.title() + " - " + s.url())
                .toList();

        return new SummarizeResponse(topic, search.searchSource(), report.executiveSummary(),
                report.keyFindings(), report.details(), sources);
    }
}
