# Configuration reference

Every deployment setting, its environment variable, its default and what it does. Defaults come
from [`application.properties`](../velocity-modules/velocity-app/src/main/resources/application.properties), which is
the single source of default values; the `*Properties` records declare none. IDE hints come from
[`additional-spring-configuration-metadata.json`](../velocity-modules/velocity-app/src/main/resources/META-INF/additional-spring-configuration-metadata.json).

How values are resolved is described in
[startup and shutdown — configuration loading](startup-and-shutdown.md#configuration-loading).
In short: `application.properties` → `./.env` → process environment, last one wins. A setting
written `${VAR}` with no default (shown as *required when active* below) is read only while its
provider or feature is active.

- [Application, server and storage](#application-server-and-storage)
- [Observability](#observability)
- [Inference](#inference)
- [Slack](#slack)
- [Agent execution](#agent-execution)
- [Local account](#local-account)
- [Delegation](#delegation)
- [Scheduling and monitoring](#scheduling-and-monitoring)
- [Knowledge, memory, skills, history and learning](#knowledge-memory-skills-history-and-learning)
- [Browser](#browser)
- [Research and file processing](#research-and-file-processing)
- [Web search](#web-search)
- [MCP connector](#mcp-connector)
- [Secrets](#secrets)
- [Code execution](#code-execution)
- [Usage pricing](#usage-pricing)
- [Example feature](#example-feature)
- [Profiles](#profiles)

## Application, server and storage

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `server.port` | `SERVER_PORT` | `8090` | HTTP port for the console and API. |
| `server.forward-headers-strategy` | `SERVER_FORWARDHEADERSSTRATEGY` | `none` | Must stay `none`; anything else fails startup. |
| `spring.application.name` | `SPRING_APPLICATION_NAME` | `velocity` | |
| `spring.mongodb.uri` | `MONGODB_URI` | `mongodb://localhost:27017/velocity` | Authoritative store for everything. |
| `spring.mongodb.representation.uuid` | `SPRING_MONGODB_REPRESENTATION_UUID` | `standard` | |
| `jobrunr.database.database-name` | `JOBRUNR_DATABASE_DATABASENAME` | `velocity` | Keep JobRunr in the same database so backups stay consistent. |
| `jobrunr.background-job-server.enabled` | — | `false` (JobRunr default) | Runs scheduled and recurring jobs. Set it explicitly in `.env`. |
| `app.time-zone` | `APP_TIME_ZONE` | empty (the machine's zone) | IANA zone of the application clock, schedule and monitoring defaults, console timestamps and the zone named in prompts. Applied while the environment is prepared; the JVM default zone is set to match. An unknown ID fails startup. |
| `logging.pattern.dateformat` | — | ISO with offset | Uses the JVM default zone, which `app.time-zone` sets. |

Code reads the configured zone through `ApplicationTime.zone()`; API timestamps carry its offset.

## Observability

See [observability](observability.md).

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `management.endpoints.web.base-path` | `MANAGEMENT_ENDPOINTS_WEB_BASEPATH` | `/api/operator/actuator` | Under the protected operator root; loopback only. |
| `management.endpoints.web.exposure.include` | `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info,metrics,prometheus` | |
| `management.endpoint.health.show-details` | `MANAGEMENT_ENDPOINT_HEALTH_SHOWDETAILS` | `always` | |
| `management.endpoint.health.show-components` | `MANAGEMENT_ENDPOINT_HEALTH_SHOWCOMPONENTS` | `always` | |
| `management.metrics.tags.application` | — | value of `spring.application.name` | Common tag on every meter. |
| `logging.pattern.level` | — | `%5p [executionId=%X{executionId:-}]` | Prints the execution ID carried in the logging context. |

## Inference

See [inference](inference.md) for how profiles, limits and token counting work.

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `inference.provider` | `INFERENCE_PROVIDER` | empty | `claude`, `openai`, `llama-cpp`, or empty to disable agent tasks and the playground. |
| `inference.primary.context-capacity` | `INFERENCE_PRIMARY_CONTEXT_CAPACITY` | *required when a provider is selected* | Nonnegative; `0` means undeclared. A positive value must fit input allowance + max tokens + safety margin. |
| `inference.primary.input-allowance` | `INFERENCE_PRIMARY_INPUT_ALLOWANCE` | *required when a provider is selected* | Positive per-request input budget, measured as the provider's token count plus 2,048 headroom. |
| `inference.primary.safety-margin` | `INFERENCE_PRIMARY_SAFETY_MARGIN` | *required when a provider is selected* | Nonnegative unused context reserve. |
| `inference.concurrency.max-requests` | `INFERENCE_CONCURRENCY_MAX_REQUESTS` | `4` | Concurrent provider requests across all profiles, including counting and open streams. |
| `inference.concurrency.queue-capacity` | `INFERENCE_CONCURRENCY_QUEUE_CAPACITY` | `100` | Waiting provider requests; full queue fails with `inference_queue_full`. |
| `inference.concurrency.max-wait-millis` | `INFERENCE_CONCURRENCY_MAX_WAIT_MILLIS` | `30000` | Wait limit for standalone callers; task requests use their remaining deadline. |

**Claude** (when `INFERENCE_PROVIDER=claude`; reads `ANTHROPIC_API_KEY`):

| Property | Environment variable | Default |
| --- | --- | --- |
| `inference.claude.model` | `CLAUDE_MODEL` | *required* |
| `inference.claude.max-tokens` | `CLAUDE_MAX_TOKENS` | *required*, positive output budget |
| `inference.claude.prompt-caching` | `CLAUDE_PROMPT_CACHING` | `true`; cache breakpoints on the system prompt, tool definitions and latest turn ([inference](inference.md#prompt-caching)) |

**OpenAI** (when `INFERENCE_PROVIDER=openai`; reads `OPENAI_API_KEY`):

| Property | Environment variable | Default |
| --- | --- | --- |
| `inference.openai.model` | `OPENAI_MODEL` | *required* |
| `inference.openai.max-tokens` | `OPENAI_MAX_TOKENS` | *required*, positive, includes reasoning |

**llama.cpp** (when `INFERENCE_PROVIDER=llama-cpp`):

| Property | Environment variable | Default |
| --- | --- | --- |
| `inference.llama-cpp.base-url` | `LLAMA_CPP_BASE_URL` | *required*, absolute HTTP(S) URL including `/v1` |
| `inference.llama-cpp.model` | `LLAMA_CPP_MODEL` | *required*, model name or `--alias` |
| `inference.llama-cpp.max-tokens` | `LLAMA_CPP_MAX_TOKENS` | *required*, positive |
| `inference.llama-cpp.api-key` | `LLAMA_CPP_API_KEY` | optional; blank uses a placeholder for unauthenticated servers |

**Cloud continuation** (one-way local-to-hosted handoff for new llama.cpp tasks):

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `inference.cloud-fallback.enabled` | `INFERENCE_CLOUD_FALLBACK_ENABLED` | **`true`** | When true, every setting below plus the hosted API key is required. Set `false` if unused. |
| `inference.cloud-fallback.provider` | `INFERENCE_CLOUD_FALLBACK_PROVIDER` | empty | `openai` or `claude`. |
| `inference.cloud-fallback.model` | `INFERENCE_CLOUD_FALLBACK_MODEL` | empty | Hosted model ID. |
| `inference.cloud-fallback.context-capacity` | `INFERENCE_CLOUD_FALLBACK_CONTEXT_CAPACITY` | *required when enabled* | |
| `inference.cloud-fallback.input-allowance` | `INFERENCE_CLOUD_FALLBACK_INPUT_ALLOWANCE` | *required when enabled* | |
| `inference.cloud-fallback.output-reserve` | `INFERENCE_CLOUD_FALLBACK_OUTPUT_RESERVE` | *required when enabled* | Max output tokens, reserved per request. |
| `inference.cloud-fallback.safety-margin` | `INFERENCE_CLOUD_FALLBACK_SAFETY_MARGIN` | *required when enabled* | |

## Slack

See [Slack](slack.md).

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `slack.enabled` | `SLACK_ENABLED` | `false` | |
| `slack.bot-token` | `SLACK_BOT_TOKEN` | empty | `xoxb-` bot token; required when enabled. |
| `slack.app-token` | `SLACK_APP_TOKEN` | empty | `xapp-` app-level token with `connections:write`; required when enabled. |
| `slack.bot-name` | `SLACK_BOT_NAME` | `Velocity` | Initial title of delivery activity. |
| `slack.workers` | `SLACK_WORKERS` | `4` | Ingress worker threads (positive). |
| `slack.queue-capacity` | `SLACK_QUEUECAPACITY` | `100` | Waiting ingress events (positive). |

## Agent execution

See [agent runtime](agent-gateway.md#task-deadline-and-limits).

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `agent.concurrency.max-active-roots` | `AGENT_CONCURRENCY_MAX_ACTIVE_ROOTS` | `4` | Root tasks running at once. |
| `agent.concurrency.queue-capacity` | `AGENT_CONCURRENCY_QUEUE_CAPACITY` | `100` | Admitted roots waiting for a slot; beyond this admission fails with HTTP 429. |
| `agent.automatic-resumption` | `AGENT_AUTOMATIC_RESUMPTION` | `true` | Relaunch `running` tasks at startup; `false` blocks them with `automatic_resumption_disabled`. |
| `agent.limits.active-millis` | `AGENT_LIMITS_ACTIVEMILLIS` | `1800000` | Active-time allowance per task (30 minutes), including queue waits, tools and children. |
| `agent.limits.task-token-ceiling` | `AGENT_LIMITS_TASK_TOKEN_CEILING` | `5000000` | Total charged tokens at which an ordinary task stops as `partial`; `0` disables. |
| `agent.limits.output-preview-chars` | `AGENT_LIMITS_OUTPUTPREVIEWCHARS` | `24576` | Characters of each tool output placed in the conversation; full output goes to an artifact. |
| `agent.limits.total-tokens` | `AGENT_LIMITS_TOTALTOKENS` | `470000` | Caps monitoring runs only. |
| `agent.limits.model-turns` | `AGENT_LIMITS_MODELTURNS` | `150` | Caps monitoring runs only. |
| `agent.limits.tool-calls` | `AGENT_LIMITS_TOOLCALLS` | `100` | Caps monitoring runs only. |
| `agent.limits.tool-timeout-millis` | `AGENT_LIMITS_TOOLTIMEOUTMILLIS` | `30000` | Caps monitoring runs only; ordinary tools use the remaining task time. |
| `agent.approval.enabled` | `AGENT_APPROVAL_ENABLED` | `false` | Hold matching tool calls for the requester's approval; see [per-call approval](agent-gateway.md#per-call-approval). |
| `agent.approval.categories` | `AGENT_APPROVAL_CATEGORIES` | `browser.interaction,system.execute,network.write` | Categories whose calls are held. Reads, questions and delegation are never held. |
| `agent.approval.tools` | `AGENT_APPROVAL_TOOLS` | *empty* | Tool names always held. |
| `agent.approval.exempt-tools` | `AGENT_APPROVAL_EXEMPT_TOOLS` | *empty* | Tool names never held. |
| `agent.live-output.model-streaming` | `AGENT_LIVE_OUTPUT_MODEL_STREAMING` | `true` | Stream root model turns on providers that support it so the console shows text as it is written; see [live output](agent-gateway.md#live-output). |

## Local account

See [getting started — the local account](getting-started.md#the-local-account).

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `agent.local-account.slack-workspace-id` | `AGENT_LOCAL_ACCOUNT_SLACK_WORKSPACE_ID` | empty | With the user ID, binds the console and operator API to the owner's Slack identity so both share one account. Set both or neither; both blank selects the console-only account. |
| `agent.local-account.slack-user-id` | `AGENT_LOCAL_ACCOUNT_SLACK_USER_ID` | empty | |

## Delegation

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `agent.delegation.enabled` | `AGENT_DELEGATION_ENABLED` | `true` | Registers `delegate_work` when at least one delegation profile exists. |
| `agent.delegation.max-children` | `AGENT_DELEGATION_MAX_CHILDREN` | `8` | Lifetime children per task, including failed and superseded ones. |
| `agent.delegation.max-objective-chars` | `AGENT_DELEGATION_MAX_OBJECTIVE_CHARS` | `4000` | Assignment length (code points). |
| `agent.delegation.max-context-chars` | `AGENT_DELEGATION_MAX_CONTEXT_CHARS` | `8000` | Optional parent context length. |
| `agent.delegation.max-references` | `AGENT_DELEGATION_MAX_REFERENCES` | `32` | Typed references per assignment. |
| `agent.delegation.max-result-bytes` | `AGENT_DELEGATION_MAX_RESULT_BYTES` | `8192` | Serialized child result envelope (minimum 512). |
| `agent.delegation.max-child-state-bytes` | `AGENT_DELEGATION_MAX_CHILD_STATE_BYTES` | `262144` | Nested state per child (minimum 16,384). |
| `agent.delegation.max-aggregate-state-bytes` | `AGENT_DELEGATION_MAX_AGGREGATE_STATE_BYTES` | `2097152` | All children together. |

## Scheduling and monitoring

See [scheduling and monitoring](scheduling-and-monitoring.md).

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `agent.scheduling.enabled` | `AGENT_SCHEDULING_ENABLED` | **`true`** | Independent of the JobRunr worker; both are needed for schedules to run. |
| `agent.scheduling.maximum-lateness` | `AGENT_SCHEDULING_MAXIMUM_LATENESS` | `60s` | Recurring occurrences later than this are skipped. |
| `agent.monitoring.concurrent-runs` | `AGENT_MONITORING_CONCURRENT_RUNS` | `4` | Unfinished monitoring runs in the installation. |
| `agent.monitoring.maximum-active-jobs` | `AGENT_MONITORING_MAXIMUM_ACTIVE_JOBS` | `50` | Open monitors per account. |
| `agent.monitoring.minimum-cadence` | `AGENT_MONITORING_MINIMUM_CADENCE` | `300s` | Shortest check interval. |
| `agent.monitoring.model-turns` | `AGENT_MONITORING_MODEL_TURNS` | `20` | Per check. |
| `agent.monitoring.tool-calls` | `AGENT_MONITORING_TOOL_CALLS` | `20` | Per check. |
| `agent.monitoring.total-tokens` | `AGENT_MONITORING_TOTAL_TOKENS` | `50000` | Per check. |
| `agent.monitoring.output-reserve` | `AGENT_MONITORING_OUTPUT_RESERVE` | `16000` | Output tokens per model request in a check, also reserved for the final report; capped by the profile's `max-tokens`. |
| `agent.monitoring.run-duration` | `AGENT_MONITORING_RUN_DURATION` | `5m` | Active time per check. |
| `agent.monitoring.results` | `AGENT_MONITORING_RESULTS` | `50` | Observations and candidate updates per result. |
| `agent.monitoring.notification-chars` | `AGENT_MONITORING_NOTIFICATION_CHARS` | `12000` | Notification length. |
| `agent.monitoring.retention` | `AGENT_MONITORING_RETENTION` | `90d` | Settled history older than this is pruned. |
| `agent.monitoring.retained-runs` | `AGENT_MONITORING_RETAINED_RUNS` | `100` | Minimum recent runs kept. |

## Knowledge, memory, skills, history and learning

See [knowledge, memory and learning](knowledge-memory-learning.md).

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `agent.knowledge.confirm-writes` | `AGENT_KNOWLEDGE_CONFIRM_WRITES` | `true` | `data_save`, `data_update` and `data_delete` pause for the requester's confirmation with a preview of the exact change, independently of `agent.approval.enabled`. |
| `agent.memory.enabled` | `AGENT_MEMORY_ENABLED` | `true` | Fact tools and admission snapshot. |
| `agent.memory.user-enabled` | `AGENT_MEMORY_USER_ENABLED` | `true` | User fact store. |
| `agent.memory.environment-enabled` | `AGENT_MEMORY_ENVIRONMENT_ENABLED` | `true` | Environment fact store. |
| `agent.memory.user-max-chars` | `AGENT_MEMORY_USER_MAX_CHARS` | `1500` | Rendered user facts. |
| `agent.memory.environment-max-chars` | `AGENT_MEMORY_ENVIRONMENT_MAX_CHARS` | `2200` | Rendered environment facts. |
| `agent.memory.profile-enabled` | `AGENT_MEMORY_PROFILE_ENABLED` | `true` | Derived user profile, written only by profile reviews. |
| `agent.memory.profile-max-chars` | `AGENT_MEMORY_PROFILE_MAX_CHARS` | `1500` | Rendered profile. |
| `agent.memory.max-proposals` | `AGENT_MEMORY_MAX_PROPOSALS` | `32` | Pending proposals per store. |
| `agent.memory.max-receipts` | `AGENT_MEMORY_MAX_RECEIPTS` | `1024` | Mutation receipts. |
| `agent.memory.max-writes-per-execution` | `AGENT_MEMORY_MAX_WRITES_PER_EXECUTION` | `6` | Learning reviews only. |
| `agent.skills.enabled` | `AGENT_SKILLS_ENABLED` | `true` | Skill tools and admission index. |
| `agent.skills.maximum-skills` | `AGENT_SKILLS_MAXIMUM_SKILLS` | `20` | Per account. |
| `agent.skills.maximum-core-chars` | `AGENT_SKILLS_MAXIMUM_CORE_CHARS` | `6000` | |
| `agent.skills.maximum-index-chars` | `AGENT_SKILLS_MAXIMUM_INDEX_CHARS` | `12000` | |
| `agent.skills.maximum-reference-chars` | `AGENT_SKILLS_MAXIMUM_REFERENCE_CHARS` | `6000` | |
| `agent.skills.retained-versions` | `AGENT_SKILLS_RETAINED_VERSIONS` | `6` | |
| `agent.skills.self-check-enabled` | `AGENT_SKILLS_SELF_CHECK_ENABLED` | `true` | Extra model turn to patch a loaded skill before a task's answer is delivered. |
| `agent.history.search-enabled` | `AGENT_HISTORY_SEARCH_ENABLED` | `true` | Message projection and search tools. |
| `agent.history.maximum-indexed-executions-per-pass` | `AGENT_HISTORY_MAXIMUM_INDEXED_EXECUTIONS_PER_PASS` | `200` | |
| `agent.history.maximum-indexed-message-chars` | `AGENT_HISTORY_MAXIMUM_INDEXED_MESSAGE_CHARS` | `16000` | |
| `agent.learning.enabled` | `AGENT_LEARNING_ENABLED` | `true` | Reflection after acknowledged delivery. |
| `agent.learning.worker-enabled` | `AGENT_LEARNING_WORKER_ENABLED` | `true` | Process-local review worker (not JobRunr). |
| `agent.learning.fact-review-every-user-turns` | `AGENT_LEARNING_FACT_REVIEW_EVERY_USER_TURNS` | `1` | Facts are reviewed after every acknowledged turn. |
| `agent.learning.notifications` | `AGENT_LEARNING_NOTIFICATIONS` | `on` | `off`, `on` (counts) or `verbose` (one line per change); Slack thread notice after a review saves something. |
| `agent.learning.profile-review-every-fact-reviews` | `AGENT_LEARNING_PROFILE_REVIEW_EVERY_FACT_REVIEWS` | `5` | Fact reviews between derived-profile refreshes. |
| `agent.learning.curator-every-days` | `AGENT_LEARNING_CURATOR_EVERY_DAYS` | `7` | Days between curator reviews; `0` disables. |
| `agent.learning.skill-review-every-tool-iterations` | `AGENT_LEARNING_SKILL_REVIEW_EVERY_TOOL_ITERATIONS` | `10` | |
| `agent.learning.maximum-input-chars` | `AGENT_LEARNING_MAXIMUM_INPUT_CHARS` | `30000` | |
| `agent.learning.maximum-millis` | `AGENT_LEARNING_MAXIMUM_MILLIS` | `120000` | |
| `agent.learning.maximum-model-turns` | `AGENT_LEARNING_MAXIMUM_MODEL_TURNS` | `5` | |
| `agent.learning.maximum-output-tokens` | `AGENT_LEARNING_MAXIMUM_OUTPUT_TOKENS` | `8192` | Profiles with a larger output allowance cannot run reviews. |
| `agent.learning.maximum-tool-calls` | `AGENT_LEARNING_MAXIMUM_TOOL_CALLS` | `8` | |
| `agent.learning.maximum-total-tokens` | `AGENT_LEARNING_MAXIMUM_TOTAL_TOKENS` | `100000` | |
| `agent.learning.profile` | `AGENT_LEARNING_PROFILE` | empty | Profile that runs reviews; empty uses the source task's pinned profile. Startup fails if the named profile is not configured. |
| `agent.learning.maximum-interaction-chars` | `AGENT_LEARNING_MAXIMUM_INTERACTION_CHARS` | `6000` | Digest size per acknowledged interaction. |
| `agent.learning.maximum-review-interactions` | `AGENT_LEARNING_MAXIMUM_REVIEW_INTERACTIONS` | `3` | Most recent interactions offered to one review. |
| `agent.learning.maximum-consecutive-failures` | `AGENT_LEARNING_MAXIMUM_CONSECUTIVE_FAILURES` | `2` | Failures in a row before the cadence skips the failing inputs. |

## Browser

See [browser](browser.md).

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `agent.browser.enabled` | `AGENT_BROWSER_ENABLED` | **`true`** | Registers browser tools. Chromium starts lazily per task. |
| `agent.browser.headless` | `AGENT_BROWSER_HEADLESS` | `false` | |
| `agent.browser.node-executable` | `AGENT_BROWSER_NODE_EXECUTABLE` | `node` | |
| `agent.browser.python-executable` | `AGENT_BROWSER_PYTHON_EXECUTABLE` | `python` | Must have the pinned Browser Use dependencies. |
| `agent.browser.launcher-script` | `AGENT_BROWSER_LAUNCHER_SCRIPT` | `runtime/browser-use-runtime/launch.cjs` | |
| `agent.browser.allowed-hosts` | `AGENT_BROWSER_ALLOWED_HOSTS` | empty | Exact hosts; empty permits public destinations. |
| `agent.browser.private-origins` | `AGENT_BROWSER_PRIVATE_ORIGINS` | empty | Exact HTTP(S) origins allowed to reach private networks. |

## Research and file processing

Research tools extract requester-attached files and fetched documents into immutable artifacts and render cited reports; see [agent runtime](agent-gateway.md).

| Property | Environment variable | Default |
| --- | --- | --- |
| `agent.research.enabled` | `AGENT_RESEARCH_ENABLED` | `true` |
| `agent.research.direct-fetch-enabled` | `AGENT_RESEARCH_DIRECT_FETCH_ENABLED` | `true` |
| `agent.research.allowed-hosts` | `AGENT_RESEARCH_ALLOWED_HOSTS` | empty (public destinations) |
| `agent.research.private-origins` | `AGENT_RESEARCH_PRIVATE_ORIGINS` | empty |
| `agent.research.max-task-requests` | `AGENT_RESEARCH_MAX_TASK_REQUESTS` | `20` |
| `agent.research.fetch-timeout` | `AGENT_RESEARCH_FETCH_TIMEOUT` | `30s` |
| `agent.research.max-redirects` | `AGENT_RESEARCH_MAX_REDIRECTS` | `5` |
| `agent.research.max-concurrent-downloads` | `AGENT_RESEARCH_MAX_CONCURRENT_DOWNLOADS` | `2` |
| `agent.research.extraction-timeout` | `AGENT_RESEARCH_EXTRACTION_TIMEOUT` | `20s` |
| `agent.research.max-extracted-chars` | `AGENT_RESEARCH_MAX_EXTRACTED_CHARS` | `1000000` |
| `agent.research.max-expanded-bytes` | `AGENT_RESEARCH_MAX_EXPANDED_BYTES` | `20971520` |
| `agent.research.max-archive-entries` | `AGENT_RESEARCH_MAX_ARCHIVE_ENTRIES` | `256` |
| `agent.research.max-artifacts` | `AGENT_RESEARCH_MAX_ARTIFACTS` | `100` |
| `agent.research.max-report-bytes` | `AGENT_RESEARCH_MAX_REPORT_BYTES` | `262144` |
| `agent.research.max-output-file-bytes` | `AGENT_RESEARCH_MAX_OUTPUT_FILE_BYTES` | `10485760` |
| `agent.research.files.pdfinfo-command` | `AGENT_RESEARCH_FILES_PDFINFO_COMMAND` | `pdfinfo` |
| `agent.research.files.render-command` | `AGENT_RESEARCH_FILES_RENDER_COMMAND` | `pdftoppm` |
| `agent.research.files.pdftotext-command` | `AGENT_RESEARCH_FILES_PDFTOTEXT_COMMAND` | `pdftotext` |
| `agent.research.files.max-units` | `AGENT_RESEARCH_FILES_MAX_UNITS` | `20` |
| `agent.research.files.page-dimension` | `AGENT_RESEARCH_FILES_PAGE_DIMENSION` | `1600` |
| `agent.research.files.max-page-image-bytes` | `AGENT_RESEARCH_FILES_MAX_PAGE_IMAGE_BYTES` | `5242880` |
| `agent.research.files.max-output-chars-per-unit` | `AGENT_RESEARCH_FILES_MAX_OUTPUT_CHARS_PER_UNIT` | `24000` |
| `agent.research.files.timeout` | `AGENT_RESEARCH_FILES_TIMEOUT` | `90s` |

Task-initiated fetches, extraction and file reading inherit the task's remaining deadline; the
timeouts above apply to standalone calls.

Files attached to a task request ([operator API — tasks](operator-api.md#tasks)) or to a Slack
message ([Slack](slack.md#using-the-agent-in-slack)) are bounded separately. They are stored with
the task's research artifacts, so `agent.research.max-artifacts` and the extraction limits above
apply to them as well. Files a later Slack reply adds to a task count against the same per-task
limits together with the files it already has.

| Property | Environment variable | Default |
| --- | --- | --- |
| `agent.uploads.enabled` | `AGENT_UPLOADS_ENABLED` | `true` |
| `agent.uploads.max-files` | `AGENT_UPLOADS_MAX_FILES` | `10` |
| `agent.uploads.max-file-bytes` | `AGENT_UPLOADS_MAX_FILE_BYTES` | `10485760` |
| `agent.uploads.max-total-bytes` | `AGENT_UPLOADS_MAX_TOTAL_BYTES` | `26214400` |

`max-total-bytes` also sets the request body ceiling of `POST /api/agent/executions` (the
Base64-encoded total plus 1 MiB); every other protected route keeps the 1 MiB body limit.

## Web search

See [web search](web-search.md).

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `web-search.enabled` | `WEB_SEARCH_ENABLED` | **`true`** | Registers `web_search`; fails startup without an API key, so set it to `false` when you have none. |
| `web-search.provider` | `WEB_SEARCH_PROVIDER` | `tavily` | Only `tavily`. |
| `web-search.api-key` | `TAVILY_API_KEY` | *empty* | |
| `web-search.base-url` | `WEB_SEARCH_BASE_URL` | `https://api.tavily.com` | |
| `web-search.max-results` | `WEB_SEARCH_MAX_RESULTS` | `5` | 1–10. |
| `web-search.max-content-chars` | `WEB_SEARCH_MAX_CONTENT_CHARS` | `2000` | 200–8000. |
| `web-search.request-timeout` | `WEB_SEARCH_REQUEST_TIMEOUT` | `20s` | |
| `web-search.search-depth` | `WEB_SEARCH_SEARCH_DEPTH` | `basic` | `basic` or `advanced`. |

## MCP connector

See [MCP connector](mcp.md).

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `mcp.enabled` | `MCP_ENABLED` | `false` | Needs at least one `mcp.servers[n]` entry; an unreachable server is reported, not fatal. |
| `mcp.connect-timeout` | `MCP_CONNECT_TIMEOUT` | `30s` | Start or reach a server and initialize; 1s–10m. |
| `mcp.request-timeout` | `MCP_REQUEST_TIMEOUT` | `60s` | One tool call; 1s–10m; capped by the task's remaining time. |
| `mcp.max-result-chars` | `MCP_MAX_RESULT_CHARS` | `100000` | 1,000–1,000,000. |
| `mcp.confirm-writes` | `MCP_CONFIRM_WRITES` | `true` | Every write waits for the requester with a preview. |
| `mcp.servers[n].*` | dotted keys in `.env` | *none* | `name`, `transport`, `command`, `args`, `directory`, `environment.*`, `url`, `headers.*`, `tools`, `category`, `request-timeout`, `max-result-chars`; see [declaring servers](mcp.md#declaring-servers). |

## Secrets

See [browser — saved credentials](browser.md#saved-website-credentials).

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `agent.secrets.enabled` | `AGENT_SECRETS_ENABLED` | `true` | Vault stays unavailable until a valid key is set. |
| `agent.secrets.key` | `AGENT_SECRETS_KEY` | empty | Base64 of exactly 32 random bytes (44 characters). |
| `agent.secrets.key-id` | `AGENT_SECRETS_KEY_ID` | `primary` | `[A-Za-z0-9_-]{1,64}`. |
| `agent.secrets.previous-keys` | `AGENT_SECRETS_PREVIOUS_KEYS` | empty | JSON object of up to 15 previous `{id: key}` entries. |
| `agent.secrets.max-records` | `AGENT_SECRETS_MAX_RECORDS` | `100` | Per account, 1–500. |
| `agent.secrets.max-receipts` | `AGENT_SECRETS_MAX_RECEIPTS` | `4096` | Per account, 16–8192. |

## Code execution

See [code execution](code-execution.md).

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `agent.code-execution.enabled` | `AGENT_CODE_EXECUTION_ENABLED` | `true` | Active only when image and installation ID are both set. |
| `agent.code-execution.image` | `AGENT_CODE_EXECUTION_IMAGE` | empty | Immutable `sha256:` image ID or repository digest. |
| `agent.code-execution.installation-id` | `AGENT_CODE_EXECUTION_INSTALLATION_ID` | empty | 8–64 lowercase letters, digits or hyphens. |
| `agent.code-execution.docker-executable` | `AGENT_CODE_EXECUTION_DOCKER_EXECUTABLE` | `docker` | |

## Usage pricing

See [usage and costs](usage-costs.md).

| Property | Environment variable | Default |
| --- | --- | --- |
| `usage.pricing-version` | `USAGE_PRICING_VERSION` | `published-2026-10-16-local-zero` |
| `usage.prices[i].provider` / `.model` | — | four entries: `openai`/`gpt-6-luna`, `claude`/`claude-haiku-4-5-20251001`, `claude`/`claude-haiku-5-5`, `llama-cpp`/`local-model` |
| `usage.prices[i].input-per-million`, `output-per-million` | — | required per entry |
| `usage.prices[i].cache-read-per-million`, `cache-creation-per-million`, `cache-creation5m-per-million`, `cache-creation1h-per-million` | — | optional |
| `usage.prices[i].long-context.input-token-threshold`, `.input-multiplier`, `.output-multiplier` | — | optional |

Overriding any `usage.prices[...]` entry replaces the whole indexed list.

## Example feature

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `example.enabled` | `EXAMPLE_ENABLED` | `false` | Only meaningful in a build packaged with `-Pexample-feature`. |

## Profiles

| Profile | File | Effect |
| --- | --- | --- |
| `console-dev` | `application-console-dev.properties` | Adds `file:./target/frontend/` to the static resource locations so a watched frontend build is served without repackaging. Changes nothing else. |
