package com.research.summarizer.api;

import com.research.summarizer.agent.InsightExtractorAgent;
import com.research.summarizer.agent.ReportGeneratorAgent;
import com.research.summarizer.agent.SearchAgent;
import com.research.summarizer.model.ErrorResponse;
import com.research.summarizer.model.Insight;
import com.research.summarizer.model.SearchResult;
import com.research.summarizer.model.SummarizeRequest;
import com.research.summarizer.model.SummarizeResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@RestController
@RequestMapping("/api/research")
public class ResearchController {

    private static final Logger log = LoggerFactory.getLogger(ResearchController.class);

    private final SearchAgent searchAgent;
    private final InsightExtractorAgent insightAgent;
    private final ReportGeneratorAgent reportAgent;

    public ResearchController(SearchAgent searchAgent, InsightExtractorAgent insightAgent, ReportGeneratorAgent reportAgent) {
        this.searchAgent = searchAgent;
        this.insightAgent = insightAgent;
        this.reportAgent = reportAgent;
    }

    /** Runs the pipeline: Search Agent -> Insight Extractor -> Report Generator. */
    @PostMapping("/summarize")
    public SummarizeResponse summarize(@Valid @RequestBody SummarizeRequest request) {
        String topic = request.topic().trim();
        int maxSources = request.maxSources() == null ? 5 : request.maxSources();
        long start = System.currentTimeMillis();
        log.info("Request received: topic='{}', maxSources={}", topic, maxSources);

        SearchResult search = searchAgent.search(topic, maxSources);
        List<Insight> insights = insightAgent.extract(topic, search);
        SummarizeResponse response = reportAgent.generate(topic, search, insights);

        log.info("Request finished: searchSource={}, {} sources, {} ms",
                response.searchSource(), response.sources().size(), System.currentTimeMillis() - start);
        return response;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest().body(new ErrorResponse(400, "Bad Request", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleBadJson(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(400, "Bad Request", "Malformed JSON request body"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleError(Exception e) {
        log.error("Request failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(500, "Internal Server Error", e.getMessage()));
    }

    /** Only the Tavily web search uses Spring's RestClient, so this is always a Tavily error. */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ErrorResponse> handleWebSearchError(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        log.error("Web search failed: Tavily returned HTTP {}", status);
        String message = status == 401
                ? "Web search failed: TAVILY_API_KEY is missing or invalid (see .env.example)"
                : "Web search failed: Tavily returned HTTP " + status;
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(500, "Internal Server Error", message));
    }
}
