package com.research.summarizer.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SummarizeRequest(
        @NotBlank(message = "topic must not be blank") String topic,
        @Min(value = 1, message = "maxSources must be between 1 and 10")
        @Max(value = 10, message = "maxSources must be between 1 and 10") Integer maxSources) {
}
