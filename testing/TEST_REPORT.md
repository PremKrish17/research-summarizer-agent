# Research Summarizer Agent: Test Scenarios

This document lists every test of the Research Summarizer Agent, with the request sent, the expected result, and the actual result.

- **Part 1, Unit tests:** automated JUnit tests. They need no running application and no API keys.
- **Part 2, API test scenarios:** 17 requests sent to the running application with Postman. Most important first, edge cases last.

The Postman collection with all 17 requests, including automatic checks, is in `postman/research-summarizer.postman_collection.json`.

---

## Part 1: Unit tests

**Command:** `./mvnw test` (Windows: `mvnw.cmd test`)

| Test class | What it tests | Tests |
|---|---|---|
| `SearchAgentTest` | Agent 1: the routing rule (`isOpenAiRelated`), search-word cleaning (`searchQuery`), and the relevance check for MCP results (`isRelevant`) | 6 |
| `InsightExtractorAgentTest` | Agent 2: the hallucination check (`keepGrounded`). A real quote is kept; formatting differences do not matter; an invented quote, a quote cited to the wrong source, an unknown source and a missing quote are all dropped | 3 |

**Expected result:** `Tests run: 9, Failures: 0, Errors: 0`, `BUILD SUCCESS`

**Result:** ✅ Pass

*[Screenshot: Maven test output]*

---

## Part 2: API test scenarios

### Setup

1. Add your own API keys to `.env` (see `.env.example`).
2. Start the application: `docker compose up --build` (or `./mvnw spring-boot:run`).
3. In Postman, import `postman/research-summarizer.postman_collection.json`.

**All requests in scenarios 1 to 14:**
- **Method:** `POST` (except scenario 14)
- **URL:** `http://localhost:8080/api/research/summarize`
- **Header:** `Content-Type: application/json`

Scenarios that call the LLM take about 30 to 120 seconds, because the app uses a free model.

### Overview

| # | Scenario | Expected | Result |
|---|---|---|---|
| 1 | OpenAI topic → MCP | 200, `MCP` | ✅ Pass |
| 2 | General topic → web search | 200, `WEB` | ✅ Pass |
| 3 | OpenAI topic not in the docs → fallback to web | 200, `WEB` | ✅ Pass |
| 4 | Fine-tuning topic → MCP (routing rule 3) | 200, `MCP` | ✅ Pass |
| 5 | Other AI vendor → web (routing rule 2) | 200, `WEB` | ✅ Pass |
| 6 | Blank topic | 400 | ✅ Pass |
| 7 | maxSources = 0 | 400 | ✅ Pass |
| 8 | Malformed JSON | 400 | ✅ Pass |
| 9 | Caller's own trace id is returned | 200, same `X-Trace-Id` | ✅ Pass |
| 10 | maxSources omitted → default 5 | 200, at most 5 sources | ✅ Pass |
| 11 | Missing topic | 400 | ✅ Pass |
| 12 | maxSources = 11 | 400 | ✅ Pass |
| 13 | maxSources has the wrong type | 400 | ✅ Pass |
| 14 | GET instead of POST | 405 | ✅ Pass |
| 15 | OpenAI Docs MCP server unavailable | 200, falls back to `WEB` | ✅ Pass |
| 16 | Tavily API key missing | 500, clear message | ✅ Pass |
| 17 | OpenRouter API key missing | Application does not start | ✅ Pass |

---

### Section A: Main scenarios (routing)

#### Scenario 1: OpenAI developer topic → OpenAI Docs MCP server

**Purpose:** shows the main path. An OpenAI developer topic is answered from the official OpenAI documentation through the MCP server.

**Request:**
```json
{ "topic": "How does streaming work in the OpenAI Responses API?", "maxSources": 3 }
```
**Expected result:**
- `200 OK`, `"searchSource": "MCP"`
- The report has `executiveSummary`, `keyFindings`, `details` and `sources`.
- Every source is a page on `developers.openai.com`.

**Result:** ✅ Pass

*[Screenshot: Postman response]*

---

#### Scenario 2: General topic → web search

**Purpose:** shows that a topic unrelated to OpenAI goes straight to Tavily web search.

**Request:**
```json
{ "topic": "Health benefits of intermittent fasting", "maxSources": 3 }
```
**Expected result:**
- `200 OK`, `"searchSource": "WEB"`
- Sources are public web pages.

**Result:** ✅ Pass

*[Screenshot: Postman response]*

---

#### Scenario 3: OpenAI topic not covered by the docs → fallback to web search

**Purpose:** shows the fallback. The topic names OpenAI, so the MCP server is tried first. The developer docs have no relevant page, so the app switches to web search.

**Request:**
```json
{ "topic": "Who founded OpenAI?", "maxSources": 3 }
```
**Expected result:**
- `200 OK`, `"searchSource": "WEB"`
- The application log shows `SearchAgent: MCP returned only 0 relevant docs, falling back to web`.

**Result:** ✅ Pass

*[Screenshot: Postman response]*
*[Screenshot: application log with the fallback line]*

---

#### Scenario 4: Fine-tuning topic → MCP (routing rule 3)

**Purpose:** shows that fine-tuning, an OpenAI query type named in the assignment, is routed to the MCP server even when no OpenAI product is named.

**Request:**
```json
{ "topic": "How does fine-tuning work?", "maxSources": 3 }
```
**Expected result:** `200 OK`, `"searchSource": "MCP"`

**Result:** ✅ Pass

*[Screenshot: Postman response]*

---

#### Scenario 5: Topic about another AI vendor → web search (routing rule 2)

**Purpose:** shows that a topic naming another AI vendor goes to the web, even when the topic contains an OpenAI-related concept. "Llama" is a Meta model, so the OpenAI docs are the wrong place to search.

**Request:**
```json
{ "topic": "Fine-tuning Llama with LoRA", "maxSources": 3 }
```
**Expected result:** `200 OK`, `"searchSource": "WEB"`

**Result:** ✅ Pass

*[Screenshot: Postman response]*

---

### Section B: Input validation and API behaviour

Scenarios 6, 7, 8, 11, 12 and 13 are rejected before any agent runs, so they answer immediately and make no LLM call.

#### Scenario 6: Blank topic

**Purpose:** a topic that contains only spaces is rejected.

**Request:**
```json
{ "topic": "   " }
```
**Expected result:**
```json
{ "status": 400, "error": "Bad Request", "message": "topic must not be blank" }
```
**Result:** ✅ Pass

*[Screenshot: Postman response]*

---

#### Scenario 7: maxSources below the minimum

**Purpose:** `maxSources` must be between 1 and 10.

**Request:**
```json
{ "topic": "How does fine-tuning work?", "maxSources": 0 }
```
**Expected result:**
```json
{ "status": 400, "error": "Bad Request", "message": "maxSources must be between 1 and 10" }
```
**Result:** ✅ Pass

*[Screenshot: Postman response]*

---

#### Scenario 8: Malformed JSON

**Purpose:** a request body that is not valid JSON gets a clear error. The closing `}` is missing on purpose.

**Request:**
```
{ "topic": "How does fine-tuning work?"
```
**Expected result:**
```json
{ "status": 400, "error": "Bad Request", "message": "Malformed JSON request body" }
```
**Result:** ✅ Pass

*[Screenshot: Postman response]*

---

#### Scenario 9: Caller's own trace id is returned and logged

**Purpose:** shows request tracing. A caller can send its own trace id. The application returns the same id in the response header and prints the id on every log line of the request, across all three agents. If no id is sent, the application creates one.

**Request:**
- **Extra header:** `X-Trace-Id: postman-test-1`
```json
{ "topic": "How does fine-tuning work?", "maxSources": 3 }
```
**Expected result:**
- `200 OK`
- Response header `X-Trace-Id: postman-test-1`
- Every log line of this request contains `[postman-test-1]`:
```
INFO [postman-test-1] ResearchController    : Request received: topic='How does fine-tuning work?', maxSources=3
INFO [postman-test-1] SearchAgent           : SearchAgent: routed to MCP, 3 docs
INFO [postman-test-1] InsightExtractorAgent : InsightExtractorAgent: attempt 1 -> ... insights, ... grounded
INFO [postman-test-1] ReportGeneratorAgent  : ReportGeneratorAgent: report written with ... key findings
INFO [postman-test-1] ResearchController    : Request finished: searchSource=MCP, ... sources, ... ms
```
**Result:** ✅ Pass

*[Screenshot: Postman response Headers tab]*
*[Screenshot: application log filtered by postman-test-1]*

---

#### Scenario 10: maxSources omitted → default value 5

**Purpose:** `maxSources` is optional. Without it, the application uses 5.

**Request:**
```json
{ "topic": "Health benefits of intermittent fasting" }
```
**Expected result:** `200 OK`, at most 5 entries in `sources`

**Result:** ✅ Pass

*[Screenshot: Postman response]*

---

#### Scenario 11: Missing topic

**Purpose:** the `topic` field is required.

**Request:**
```json
{ "maxSources": 3 }
```
**Expected result:**
```json
{ "status": 400, "error": "Bad Request", "message": "topic must not be blank" }
```
**Result:** ✅ Pass

*[Screenshot: Postman response]*

---

#### Scenario 12: maxSources above the maximum

**Purpose:** upper limit of `maxSources`.

**Request:**
```json
{ "topic": "How does fine-tuning work?", "maxSources": 11 }
```
**Expected result:**
```json
{ "status": 400, "error": "Bad Request", "message": "maxSources must be between 1 and 10" }
```
**Result:** ✅ Pass

*[Screenshot: Postman response]*

---

#### Scenario 13: maxSources has the wrong type

**Purpose:** text where a number is expected is rejected.

**Request:**
```json
{ "topic": "How does fine-tuning work?", "maxSources": "five" }
```
**Expected result:**
```json
{ "status": 400, "error": "Bad Request", "message": "Malformed JSON request body" }
```
**Result:** ✅ Pass

*[Screenshot: Postman response]*

---

#### Scenario 14: Wrong HTTP method

**Purpose:** the endpoint accepts only POST.

**Request:** `GET http://localhost:8080/api/research/summarize` (no body)

**Expected result:**
- `405 Method Not Allowed`, response header `Allow: POST`
- Spring answers before the controller runs, so the body has Spring's default error format:
```json
{ "timestamp": "...", "status": 405, "error": "Method Not Allowed", "path": "/api/research/summarize" }
```
**Result:** ✅ Pass

*[Screenshot: Postman response]*

---

### Section C: Edge cases (failures and recovery)

#### Why these scenarios need a separate setup

Scenarios 15 to 17 test how the application behaves when something it depends on is **unavailable or misconfigured**: the OpenAI Docs MCP server, the Tavily API key, or the OpenRouter API key. These failures cannot be caused by a request alone. For example, the real OpenAI Docs server cannot be switched off, and the API keys are read only once, at startup.

So each of these scenarios starts a **separate test container**: a second copy of the same application, built from the same Docker image, with **one setting deliberately broken**. Each test container runs on its own port, so the normal application on port 8080 stays unchanged.

| Scenario | Test container | Port | Setting changed on purpose |
|---|---|---|---|
| 15. OpenAI docs server down | `test-mcp-down` | 8081 | `MCP_URL` points to an address where no server is listening |
| 16. Missing Tavily key | `test-no-tavily` | 8082 | `TAVILY_API_KEY` is empty |
| 17. Missing OpenRouter key | `test-no-openrouter` | 8083 | `OPENROUTER_API_KEY` is empty |

The changed setting is passed with `-e` when the container starts. Docker passes the setting to the app as an environment variable, which replaces the same value from the `.env` file. No code or configuration file is changed for these tests.

**Prerequisites**
- Docker Desktop is running.
- The image is built (`docker compose up --build`).
- Commands are run from the project folder, because they read the API keys from `.env`.

---

#### Scenario 15: OpenAI Docs MCP server unavailable → fallback to web search

**Purpose:** shows that the application still answers an OpenAI topic when the OpenAI Docs MCP server cannot be reached, by falling back to Tavily web search.

**Setup:**
```
docker run --rm -d --name test-mcp-down -p 8081:8080 --env-file .env -e MCP_URL=http://127.0.0.1:9/mcp research-summarizer-agent-research-summarizer
```
**Request:** `POST http://localhost:8081/api/research/summarize`
```json
{ "topic": "How does streaming work in the OpenAI Responses API?", "maxSources": 3 }
```
**Expected result:**
- The application starts normally, even though the MCP server is unreachable. The MCP client is created only on first use (`@Lazy`).
- Response `200 OK` with `"searchSource": "WEB"`.
- The container log shows `SearchAgent: MCP failed (...), falling back to web`.

**Result:** ✅ Pass

*[Screenshot 1: Postman response]*
*[Screenshot 2: container log with the fallback line]*

**Clean-up** (after the log screenshot, because `--rm` deletes the log): `docker stop test-mcp-down`

---

#### Scenario 16: Tavily API key missing → clear error message

**Purpose:** shows that a missing or invalid Tavily key returns a clear, readable error instead of the raw error text from Tavily.

**Setup:**
```
docker run --rm -d --name test-no-tavily -p 8082:8080 --env-file .env -e TAVILY_API_KEY= research-summarizer-agent-research-summarizer
```
**Request:** `POST http://localhost:8082/api/research/summarize`
```json
{ "topic": "Health benefits of intermittent fasting", "maxSources": 3 }
```
**Expected result:**
- Response `500 Internal Server Error`:
```json
{ "status": 500, "error": "Internal Server Error", "message": "Web search failed: TAVILY_API_KEY is missing or invalid (see .env.example)" }
```
- The container log shows `Web search failed: Tavily returned HTTP 401`.

**Result:** ✅ Pass

*[Screenshot: Postman response]*

**Clean-up:** `docker stop test-no-tavily`

---

#### Scenario 17: OpenRouter API key missing → application refuses to start (fail fast)

**Purpose:** shows that a missing LLM key is detected immediately at startup with a clear message, instead of failing later on a user's first request.

**Setup:** run without `--rm`, so the stopped container and its log remain available for inspection:
```
docker run --name test-no-openrouter -p 8083:8080 --env-file .env -e OPENROUTER_API_KEY= research-summarizer-agent-research-summarizer
```
**Request:** `POST http://localhost:8083/api/research/summarize`
```json
{ "topic": "How does fine-tuning work?", "maxSources": 3 }
```
**Expected result:**
- The container stops at startup with status `Exited (1)`. The log shows `OPENROUTER_API_KEY is not set (see .env.example)`.
- Postman cannot connect: `Error: connect ECONNREFUSED 127.0.0.1:8083`. This is expected, because no application is running on port 8083.

**Result:** ✅ Pass

*[Screenshot 1: container log in Docker Desktop. In the Containers tab, switch off "Only show running containers" to see the stopped container]*
*[Screenshot 2: Postman error]*

**Clean-up:** `docker rm test-no-openrouter`

---

#### Summary of the edge cases

| Dependency | What happens when it fails | Why |
|---|---|---|
| OpenAI Docs MCP server | The request still succeeds, using web search | The MCP client is created lazily, and every MCP failure falls back to Tavily |
| Tavily API key | Web-search requests fail with a clear 500 message | A dedicated error handler turns Tavily's HTTP error into a readable message |
| OpenRouter API key | The application does not start | The key is checked at startup (fail fast), so a misconfiguration is found immediately |

---

## Final summary

| Part | Tests | Passed |
|---|---|---|
| Unit tests (JUnit) | 9 | 9 |
| API scenarios: main routing (Section A) | 5 | 5 |
| API scenarios: validation and API behaviour (Section B) | 9 | 9 |
| API scenarios: edge cases (Section C) | 3 | 3 |
| **Total** | **26** | **26** |
