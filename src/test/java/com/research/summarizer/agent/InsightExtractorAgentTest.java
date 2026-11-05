package com.research.summarizer.agent;

import com.research.summarizer.model.Insight;
import com.research.summarizer.model.Source;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests for the hallucination check of Agent 2. */
class InsightExtractorAgentTest {

    private static final List<Source> SOURCES = List.of(
            new Source("S1", "Streaming", "https://example.com/s1",
                    "Set **stream=true** to receive events as they are generated."),
            new Source("S2", "Pricing", "https://example.com/s2",
                    "Batch requests cost 50% less than normal requests."));

    @Test
    void insightWhoseQuoteIsInItsSourceIsKept() {
        Insight insight = new Insight("FACT", "Streaming sends events.",
                "receive events as they are generated", "S1");
        assertEquals(List.of(insight), InsightExtractorAgent.keepGrounded(List.of(insight), SOURCES));
    }

    @Test
    void formattingDifferencesInTheQuoteDoNotMatter() {
        // The source has markdown bold and lower case; the quote has neither.
        Insight insight = new Insight("FACT", "Enable streaming.", "Set stream=TRUE to receive events", "S1");
        assertEquals(1, InsightExtractorAgent.keepGrounded(List.of(insight), SOURCES).size());
    }

    @Test
    void inventedOrWronglyCitedInsightsAreDropped() {
        List<Insight> insights = List.of(
                new Insight("FACT", "Invented.", "streaming is free forever", "S1"),
                new Insight("STATISTIC", "Wrong source.", "cost 50% less", "S1"),
                new Insight("FACT", "Unknown source.", "receive events", "S9"),
                new Insight("FACT", "No quote.", null, "S1"));
        assertEquals(List.of(), InsightExtractorAgent.keepGrounded(insights, SOURCES));
    }
}
