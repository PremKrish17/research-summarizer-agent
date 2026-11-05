package com.research.summarizer.model;

import java.util.List;

public record SummarizeResponse(
        String topic,
        String searchSource,
        String executiveSummary,
        List<String> keyFindings,
        String details,
        List<String> sources) {
}
