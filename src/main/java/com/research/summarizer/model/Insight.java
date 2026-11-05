package com.research.summarizer.model;

/** One insight from the Insight Extractor. type: FACT, STATISTIC, DEFINITION or QUOTE. */
public record Insight(String type, String statement, String quote, String sourceId) {
}
