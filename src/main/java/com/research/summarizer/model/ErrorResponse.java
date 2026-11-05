package com.research.summarizer.model;

public record ErrorResponse(int status, String error, String message) {
}
