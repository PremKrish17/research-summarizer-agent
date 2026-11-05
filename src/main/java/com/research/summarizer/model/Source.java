package com.research.summarizer.model;

/** One retrieved document. id is "S1", "S2", ... and is what the agents cite. */
public record Source(String id, String title, String url, String content) {
}
