package com.research.summarizer.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the routing logic. */
class SearchAgentTest {

    @Test
    void openAiTopicsAreRoutedToMcp() {
        assertTrue(SearchAgent.isOpenAiRelated("How does streaming work in the OpenAI Responses API?"));
        assertTrue(SearchAgent.isOpenAiRelated("Getting started with the Agents SDK"));
        assertTrue(SearchAgent.isOpenAiRelated("gpt-4o pricing"));
    }

    @Test
    void generalTopicsAreRoutedToWeb() {
        assertFalse(SearchAgent.isOpenAiRelated("Health benefits of intermittent fasting"));
        assertFalse(SearchAgent.isOpenAiRelated("History of the Roman Empire"));
        // "agents" alone is too generic to mean the OpenAI Agents SDK
        assertFalse(SearchAgent.isOpenAiRelated("AI agents in healthcare"));
    }

    @Test
    void fineTuningAloneIsRoutedToMcpUnlessAnotherVendorIsNamed() {
        assertTrue(SearchAgent.isOpenAiRelated("How does fine-tuning work?"));
        assertTrue(SearchAgent.isOpenAiRelated("Fine tuning best practices"));
        assertFalse(SearchAgent.isOpenAiRelated("Fine-tuning Llama with LoRA"));
    }

    @Test
    void twoConceptTermsAreRoutedToMcpUnlessAnotherVendorIsNamed() {
        assertTrue(SearchAgent.isOpenAiRelated("Function calling and structured outputs"));
        assertFalse(SearchAgent.isOpenAiRelated("Embeddings and function calling with Cohere"));
        // one concept is not enough
        assertFalse(SearchAgent.isOpenAiRelated("What are embeddings?"));
    }

    @Test
    void searchQueryDropsQuestionWords() {
        assertEquals("streaming openai responses api",
                SearchAgent.searchQuery("How does streaming work in the OpenAI Responses API?"));
    }

    @Test
    void irrelevantDocsHitsAreRejected() {
        assertTrue(SearchAgent.isRelevant("OpenAI Responses API streaming", "Streaming API responses | OpenAI API"));
        // The docs search really returns this page for an unrelated query.
        assertFalse(SearchAgent.isRelevant("best pizza in naples", "Best practices for ChatGPT Edu"));
    }
}
