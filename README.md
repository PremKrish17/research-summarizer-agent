# Research Summarizer Agent

A Java (Spring Boot + LangChain4j) system with **three agents** that takes a research topic and returns a structured summary report. OpenAI developer topics are answered from the **OpenAI Docs MCP server**. Other topics use **Tavily web search**.

## 1. Setup

**You need:** Java 17+ (or Docker), a free [OpenRouter](https://openrouter.ai) API key and a free [Tavily](https://tavily.com) API key.

**1. Add your own API keys.** This repository contains no keys, so **the app does not work until you add your own.** Both keys are free.

| Key | Used by | Get it at | Without the key |
|---|---|---|---|
| `OPENROUTER_API_KEY` | Agents 2 and 3 (the LLM) | https://openrouter.ai/keys | The app stops at startup with `OPENROUTER_API_KEY is not set` |
| `TAVILY_API_KEY` | Agent 1 (web search) | https://app.tavily.com | Topics that need web search fail with error 500: `Web search failed: TAVILY_API_KEY is missing or invalid` |

The OpenAI Docs MCP server needs no key.

```bash
cp .env.example .env
# open .env and paste your keys after OPENROUTER_API_KEY= and TAVILY_API_KEY=
```

`.env` is in `.gitignore`, so it is never pushed to Git. The app reads it at startup (`spring.config.import` in `application.yml`), and `docker compose` passes it into the container (`env_file`). Normal environment variables also work.

**2. Run**

```bash
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
# or
docker compose up --build
```

The app listens on port 8080. The Docker image is built in two stages (Maven builds the jar, then only the jar is copied into a small Java 17 runtime image), runs as a non-root user, and contains no keys: `docker compose` passes them in from `.env` at runtime.

**3. Call it (one curl command)**

```bash
curl -X POST http://localhost:8080/api/research/summarize \
  -H "Content-Type: application/json" \
  -d '{"topic": "How does streaming work in the OpenAI Responses API?", "maxSources": 4}'
```

A request takes about 30 to 90 seconds, because it makes two LLM calls on a free model.

On Windows PowerShell, `curl` is a different command; use `curl.exe` or Git Bash, or import the Postman collection below.

**Unit tests:** `./mvnw test` (Windows: `mvnw.cmd test`). 9 tests: routing and search helpers of Agent 1, and the quote check of Agent 2. No API keys needed.

**API test scenarios:** import `postman/research-summarizer.postman_collection.json` into Postman. It has 17 requests (main routing scenarios first, then input validation, then edge cases), each with automatic checks. Every test, with its request, expected result and setup, is described in the test report [testing/TEST_REPORT.md](testing/TEST_REPORT.md). `testing/TEST_REPORT.docx` is the same report with screenshots of each test run.

**Logs:** every request gets a trace id, returned in the `X-Trace-Id` response header (or reused from that request header if the caller sends one). The trace id is printed on every log line of the request, across all three agents:

```
INFO [2b7b9800] ResearchController : Request received: topic='How does fine-tuning work?', maxSources=3
INFO [2b7b9800] SearchAgent        : SearchAgent: routed to MCP, 3 docs
INFO [2b7b9800] InsightExtractorAgent : InsightExtractorAgent: attempt 1 -> 14 insights, 13 grounded
INFO [2b7b9800] ReportGeneratorAgent  : ReportGeneratorAgent: report written with 6 key findings
INFO [2b7b9800] ResearchController : Request finished: searchSource=MCP, 1 sources, 65397 ms
```

Log lines written outside a request (for example at startup) have no trace id, so they show no brackets: `INFO 1 --- ... Started ResearchSummarizerApplication`.

For JSON logs (one JSON object per line, with `traceId` as a field), set `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`.

## 2. Architecture

```
          POST /api/research/summarize  {topic, maxSources}
                              │
                   ┌──────────▼──────────┐
                   │  ResearchController │  validates input, runs the 3 agents in order
                   └──────────┬──────────┘
                              │
   ┌──────────────────────────▼───────────────────────────┐
   │ Agent 1: SearchAgent                                 │
   │   OpenAI topic?  yes → OpenAI Docs MCP ──┐           │
   │                  no  → Tavily web search │ too few   │
   │                        ▲─────────────────┘ results   │
   └──────────────────────────┬───────────────────────────┘
                              │ SearchResult (searchSource, sources S1..Sn)
   ┌──────────────────────────▼───────────────────────────┐
   │ Agent 2: InsightExtractorAgent  (LLM)                │
   │   extracts facts / statistics / definitions / quotes │
   │   drops any insight whose quote is not in its source │
   └──────────────────────────┬───────────────────────────┘
                              │ List<Insight>
   ┌──────────────────────────▼───────────────────────────┐
   │ Agent 3: ReportGeneratorAgent  (LLM)                 │
   │   writes Executive Summary, Key Findings, Details    │
   │   builds the Sources list from the search results    │
   └──────────────────────────┬───────────────────────────┘
                              ▼
                        JSON response
```

### Code layout

```
src/main/java/com/research/summarizer/
├── ResearchSummarizerApplication.java   Spring Boot entry point
├── config/LlmConfig.java                LLM (OpenRouter) and MCP client setup
├── api/ResearchController.java          REST endpoint and error handling
├── api/TraceIdFilter.java               Gives each request a trace id for the logs
├── agent/SearchAgent.java               Agent 1
├── agent/InsightExtractorAgent.java     Agent 2
├── agent/ReportGeneratorAgent.java      Agent 3
└── model/                               Small data records (request, response, Source, Insight, ...)
src/main/resources/
├── application.yml                      Configuration (keys come from environment variables)
└── prompts/                             System prompts for Agent 2 and Agent 3
src/test/java/com/research/summarizer/agent/
├── SearchAgentTest.java                 Routing rules, relevance check, search query (6 tests)
└── InsightExtractorAgentTest.java       Quote check of Agent 2 (3 tests)
samples/                                 Example responses (MCP, web, 400 error)
postman/                                 Postman collection with 17 API test scenarios
testing/                                 Test report (Markdown, and Word with screenshots)
Dockerfile, docker-compose.yml           Container build and run
.env.example                             Template for your own API keys
```

### Configuration

All settings are in `application.yml`. Any of them can be overridden with an environment variable (for example `MCP_URL` overrides `mcp.url`).

| Setting | Value | Meaning |
|---|---|---|
| `openrouter.base-url` | `https://openrouter.ai/api/v1` | OpenRouter's OpenAI-compatible API |
| `openrouter.api-key` | from `OPENROUTER_API_KEY` | LLM key (required at startup) |
| `openrouter.model` | `nvidia/nemotron-3.5-super-vl:free` | The pinned LLM, temperature 0 |
| `openrouter.fallback-models` | `google/gemma-4-31b-it:free`, `google/gemma-4-26b-a4b-it:free` | Used in this order only if the main model fails |
| `tavily.api-key` | from `TAVILY_API_KEY` | Web search key |
| `mcp.url` | `https://developers.openai.com/mcp` | OpenAI Docs MCP server (Streamable HTTP, no key) |

Timeouts: 180 seconds for an LLM call, 30 seconds for an MCP call.

## 3. Routing logic

The routing decision is **deterministic**. It uses fixed rules and never asks an LLM.

**Step 1: Is the topic OpenAI developer-related?** (`SearchAgent.isOpenAiRelated`)

The topic is lower-cased, and a "squashed" copy is made with spaces, `_` and `-` removed, so "Open AI", "open-ai" and "OpenAI" all become `openai`, and "Fine-Tuning" becomes `finetuning`. Then four rules are checked in order. The first rule that applies decides.

| # | Rule | Result | Terms (`SearchAgent` constant) |
|---|---|---|---|
| 1 | An OpenAI product, API or model name appears | MCP | `OPENAI_TERMS`: openai, chatgpt, responses api, chat completions, assistants api, realtime api, agents sdk, agentkit, agent builder, apps sdk, chatkit, dall-e, whisper, sora, codex, gpt-image, gpt-realtime, gpt-oss, text-embedding, code interpreter, custom gpts…<br>`MODEL_NAME` pattern: `gpt-4o`, `gpt 5`, `gpt-4.1`, `o1`, `o3-mini`, and future names like `gpt-6` |
| 2 | Another AI vendor or framework is named | WEB | `COMPETITOR`: anthropic, claude, gemini, llama, mistral, hugging face, cohere, deepseek, qwen, langchain, langgraph, llamaindex, crewai, autogen (whole words) |
| 3 | Fine-tuning is mentioned, or two or more OpenAI-documented LLM concepts are | MCP | `PRIMARY_CONCEPT_TERMS`: fine-tuning (one is enough)<br>`CONCEPT_TERMS`: embeddings, function calling, tool calling, structured outputs, JSON mode, prompt caching, reasoning effort, moderation, vector stores, file search, batch API, distillation (two needed) |
| 4 | None of the above | WEB | |

This covers each query type in the assignment's routing table: OpenAI API, Models and Responses API (rule 1), SDK and Agents (rule 1, through the product names), and Fine-tuning (rule 3).

Deliberately **not** used as triggers: bare `gpt`, `ai`, `llm`, `agents`, `sdk`, `api`, `tokens`, `prompt`. They are too generic. "AI agents in healthcare" or "How to install the Python SDK" have nothing to do with OpenAI, and these words would send most AI questions to the OpenAI docs. A topic about the OpenAI Agents SDK is still caught by naming it ("Agents SDK", "AgentKit") or by naming OpenAI.

A wrong "yes" here is cheap. Step 2 checks whether the docs actually cover the topic and falls back to the web if they don't.

**Step 2: Did MCP return enough?** (OpenAI topics only)
1. Question words are removed to build the search query (`"How does streaming work in the OpenAI Responses API?"` becomes `"streaming openai responses api"`). Full questions gave much worse search results.
2. The MCP tool `search_openai_docs` is called (up to 20 results).
3. A result counts only if it is **relevant**: its title or URL contains at least two-thirds of the topic's keywords. This is needed because the docs search always returns *something*. For example, "best pizza in naples" returns "Best practices for ChatGPT Edu". The words "openai", "api" and "sdk" are not counted as keywords, because every docs page contains them. The same page is never used twice.
4. Each relevant page is fetched with the MCP tool `fetch_openai_doc`, until `maxSources` pages are collected. Pages with less than 100 characters are skipped, and each page is cut to 6,000 characters to keep the LLM prompt small.
5. If at least 2 pages have content (1 if `maxSources` is 1), the result is **MCP**. Otherwise, or if the MCP server fails, the agent falls back to **web search**.

**Web search** (Tavily, `search_depth: advanced`) returns up to `maxSources` results. Each source is Tavily's relevant snippet plus the page text, with the same 100-character minimum and 6,000-character limit. If the web search finds nothing, the request fails with 500.

Sources are numbered `S1`, `S2`, ... so the agents can cite them.

| Topic | Route |
|---|---|
| How does streaming work in the OpenAI Responses API? | MCP (rule 1: "openai", "responses api") |
| gpt-4o pricing | MCP (rule 1: model name) |
| Getting started with the Agents SDK | MCP (rule 1: "agents sdk") |
| How does fine-tuning work? | MCP (rule 3: fine-tuning) |
| Function calling and structured outputs | MCP (rule 3: two concepts) |
| Fine-tuning Llama with LoRA | WEB (rule 2: "llama" is another vendor) |
| What are embeddings? | WEB (rule 4: only one concept) |
| AI agents in healthcare | WEB (rule 4: "agents" alone is too generic) |
| Health benefits of intermittent fasting | WEB (rule 4) |
| Who founded OpenAI? (OpenAI term, but not in the developer docs) | MCP is tried, not enough relevant docs, so WEB |

## 4. API contract

**`POST /api/research/summarize`**

Request:

```json
{ "topic": "string (required)", "maxSources": 5 }
```

`maxSources` is optional (default 5, range 1 to 10).

Response `200`:

```json
{
  "topic": "How does streaming work in the OpenAI Responses API?",
  "searchSource": "MCP",
  "executiveSummary": "Streaming in the OpenAI Responses API allows clients to ... [S1].",
  "keyFindings": ["Enable streaming by setting stream=True in the Responses API request [S1].", "..."],
  "details": "Paragraphs with citations like [S1] and [S2].",
  "sources": ["[S1] Streaming API responses | OpenAI API - https://developers.openai.com/api/docs/guides/streaming-responses"]
}
```

Errors (structured body):

```json
{ "status": 400, "error": "Bad Request", "message": "topic must not be blank" }
```

| Status | When |
|---|---|
| 400 | Missing or blank topic, `maxSources` out of range, or malformed JSON |
| 405 | Any method other than POST (Spring's default error body) |
| 500 | Search or LLM failure, or no sources or insights could be found. A Tavily error gets a short, clear message, e.g. `Web search failed: TAVILY_API_KEY is missing or invalid (see .env.example)` |

**Header:** every response carries `X-Trace-Id`. Send your own `X-Trace-Id` (letters, digits and `-`, up to 64 characters) to have it reused; otherwise a new 8-character id is generated. The same id appears on every log line of the request.

**Sample responses** are in `samples/`:
- `openai-mcp-response.json`: OpenAI topic, served by MCP
- `general-web-response.json`: general topic, served by web search
- `error-400-response.json`: validation error

## 5. LLM choice and hallucination control

**Provider:** OpenRouter. It is free and uses the OpenAI API format, so LangChain4j's `OpenAiChatModel` works with only a different `baseUrl`.

**A pinned model, not `openrouter/free`:** `openrouter/free` picks a different model on every request, so answers vary and you cannot tell which model wrote a report. This app uses one fixed model (`nvidia/nemotron-3.5-super-vl:free`), with temperature 0. Two backup models are passed in OpenRouter's `models` list. They are used, in order, only if the main model fails (free models are often rate-limited).

**Keeping answers grounded:**
1. Agent 2 sees only the retrieved sources. Agent 3 sees only Agent 2's insights. Both prompts say "use ONLY this information".
2. Every insight must include an exact quote from its source. Code checks that the quote really appears in that source and drops the insight if it doesn't (`InsightExtractorAgent.keepGrounded`).
3. If no insight passes the quote check, Agent 2 asks the LLM once more; if there are still none, the request fails with 500 instead of returning an ungrounded report.
4. The `sources` list is built by code from the search results, so the LLM cannot invent URLs. It lists only the sources that at least one insight was taken from.

## 6. Design decisions and trade-offs

- **Simple fixed pipeline.** The three agents always run in the same order, so the controller calls them one after another. An LLM-driven orchestrator (LangGraph or CrewAI style) would add cost and unpredictability without any benefit here.
- **LangChain4j as the agent framework.** `AiServices` turns a Java interface with prompt annotations into an LLM agent and parses the JSON reply directly into Java records. `DefaultMcpClient` with `StreamableHttpMcpTransport` is the MCP client.
- **Search Agent calls MCP tools directly.** It doesn't let an LLM choose tools. The steps are always "search docs, then fetch pages", so plain code is faster and gives the same result every time.
- **Keyword routing.** Easy to explain and test, but a keyword list can miss topics. The relevance check and web fallback make mistakes safe: a wrongly routed topic still gets a web answer.
- **Lazy MCP client.** The MCP client connects on first use, not at startup. The app starts even when the OpenAI docs server is down, and web-only topics never connect to it. The cost: a broken MCP address is noticed only on the first OpenAI topic (which then falls back to web).
- **Fail fast on the LLM key, not on the Tavily key.** A missing OpenRouter key stops startup, because no request can succeed without the LLM. A missing Tavily key is reported on the first web search, with a clear message, because OpenAI topics answered by MCP still work without it.
- **Trace id through the logging MDC.** A servlet filter stores the id per thread, so it reaches every agent's log lines without being passed as a parameter.
- **Retry once.** Free models sometimes return broken JSON, so each LLM agent tries twice before failing.

## 7. Status

**Done:** all mandatory items (3 agents, MCP over Streamable HTTP, Tavily web search, deterministic routing with fallback, REST endpoint with 400/500 errors, README, sample responses, design decisions), plus all three bonus items: unit tests (routing logic, and the Agent 2 quote check), Docker Compose, and logging with request trace ids across agent hops.

**Tested:** 9 unit tests and 17 API scenarios, all passing (see the test report [testing/TEST_REPORT.md](testing/TEST_REPORT.md)).
