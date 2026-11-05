package com.research.summarizer.model;

import java.util.List;

/** Output of the Search Agent. searchSource is "MCP" or "WEB". */
public record SearchResult(String searchSource, List<Source> sources) {
}
