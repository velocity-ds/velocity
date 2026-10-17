# Getting started

This guide takes a fresh checkout to a running Velocity with the operator console, then shows how
to turn on agent tasks and the optional integrations. For what happens inside the process
while it boots, see [startup and shutdown](startup-and-shutdown.md).

- [Prerequisites](#prerequisites)
- [Minimal .env](#minimal-env)
- [First run](#first-run)
- [The local account](#the-local-account)
- [Enable agent tasks](#enable-agent-tasks)
- [Enable optional integrations](#enable-optional-integrations)
- [Build a release JAR](#build-a-release-jar)
- [Next steps](#next-steps)

## Prerequisites

| Requirement | Needed for |
| --- | --- |
| JDK 25 | Always. Use the checked-in Maven wrapper; no separate Maven install is needed. |
| MongoDB (default `mongodb://localhost:27017/velocity`) | Always. Startup recovery reads saved executions. |
| Node.js 22.12+ and npm | Building the operator console (`-Pfrontend`) and the browser runtime. |
| An inference provider | Agent tasks and the playground: an Anthropic or OpenAI API key, or a llama.cpp server with `/v1/responses` and `/v1/responses/input_tokens`. |
| Slack app with bot and app-level tokens | Slack conversations and scheduled Slack posts. See [Slack](slack.md). |
| Python 3.11+, Node.js and Chromium (installed by the browser runtime) | Browser tools. See [browser](browser.md). |
| Docker Engine with Linux containers | Python code execution. See [code execution](code-execution.md). |
| Poppler (`pdfinfo`, `pdftoppm`, `pdftotext`) and a vision-capable model | Reading PDF files attached to tasks. See [configuration](configuration.md#research-and-file-processing). |

## Minimal .env

Create a `.env` file in the repository root (it is Git-ignored). Spring imports it as Java
properties, so write unquoted `KEY=value` lines. Process environment variables take
precedence.

This starting point boots with only MongoDB. It turns off every integration that would need
credentials or a JobRunr worker, including the two that are **on** in
`application.properties` (cloud continuation and web search). `.env.example` in the repository
root carries the same starting point with every optional setting commented:

```properties
SERVER_PORT=8090
SERVER_FORWARDHEADERSSTRATEGY=none
# Empty uses this machine's time zone.
APP_TIME_ZONE=

MONGODB_URI=mongodb://localhost:27017/velocity
JOBRUNR_DATABASE_DATABASENAME=velocity

# Inference: empty disables agent tasks and the playground.
INFERENCE_PROVIDER=
INFERENCE_CLOUD_FALLBACK_ENABLED=false

# Integrations
SLACK_ENABLED=false
AGENT_BROWSER_ENABLED=false
AGENT_BROWSER_HEADLESS=true
AGENT_CODE_EXECUTION_ENABLED=false
WEB_SEARCH_ENABLED=false
MCP_ENABLED=false

# Scheduling and JobRunr are separate switches; set both.
AGENT_SCHEDULING_ENABLED=false
jobrunr.background-job-server.enabled=false
```

Keep credentials only in `.env` or the process environment, never in committed files.

## First run

Start MongoDB, then from the repository root:

```sh
sh ./mvnw -Pfrontend -pl velocity-modules/velocity-app -am spring-boot:run
```

On Windows use `.\mvnw.cmd` in place of `sh ./mvnw`. `-Pfrontend` runs `npm ci` and the Vite
build, then serves the console from Spring. Omit it for a backend-only run.

Check that it is up:

```sh
curl http://localhost:8090/velocity/hello
```

The response is `Hello from Velocity!`. Open `http://localhost:8090/` for the console and
**Integrations & System** (`/system`) for the effective configuration.

## The local account

The console and the operator API act as one account, chosen by configuration rather than by
anything in a request:

| `agent.local-account.*` | Account |
| --- | --- |
| Both blank (default) | The console-only account: workspace `local`, user `operator`. |
| `slack-workspace-id` and `slack-user-id` set | The account of that Slack user, so Slack messages from the installation's owner and the console share tasks, knowledge, schedules, memory and skills. |

The account record is created in the `accounts` collection on first use with the same
registration path a verified Slack message uses, and its stored identity is checked on every
user-resource request. A fresh database needs nothing inserted by hand. If MongoDB cannot be
read, those routes return **HTTP 503**; routes that need no account (administrative task
listing and inspection, system status and the playground) keep working.

Setting one of the two values without the other fails startup. Changing them later points the
console at a different account; it does not move data between accounts. Slack shows a member
ID in a profile's menu ("Copy member ID"); the workspace ID starts with `T` and appears in the
workspace's admin URLs. See [security model](security.md#accounts-and-ownership).

## Enable agent tasks

Choose exactly one provider. Every provider also needs three explicit request limits; they
have no defaults.

| `INFERENCE_PROVIDER` | Required settings |
| --- | --- |
| `claude` | `ANTHROPIC_API_KEY`, `CLAUDE_MODEL`, positive `CLAUDE_MAX_TOKENS` |
| `openai` | `OPENAI_API_KEY`, `OPENAI_MODEL`, positive `OPENAI_MAX_TOKENS` |
| `llama-cpp` | `LLAMA_CPP_BASE_URL` (including `/v1`), `LLAMA_CPP_MODEL`, positive `LLAMA_CPP_MAX_TOKENS`, and `LLAMA_CPP_API_KEY` if the server requires it |

All providers: `INFERENCE_PRIMARY_CONTEXT_CAPACITY`, `INFERENCE_PRIMARY_INPUT_ALLOWANCE` and
`INFERENCE_PRIMARY_SAFETY_MARGIN`. A positive capacity must hold the input allowance plus the
provider's max tokens plus the margin.

Example for a local llama.cpp server with 140,032 tokens per slot:

```properties
INFERENCE_PROVIDER=llama-cpp
LLAMA_CPP_BASE_URL=http://127.0.0.1:8080/v1
LLAMA_CPP_MODEL=local-model
LLAMA_CPP_MAX_TOKENS=40000
INFERENCE_PRIMARY_CONTEXT_CAPACITY=140032
INFERENCE_PRIMARY_INPUT_ALLOWANCE=90000
INFERENCE_PRIMARY_SAFETY_MARGIN=10000
INFERENCE_CLOUD_FALLBACK_ENABLED=false
```

Restart, then create a task from **Tasks → New task**, or:

```sh
curl -X POST http://localhost:8090/api/agent/executions \
  -H 'Content-Type: application/json' \
  -d '{"request":"What day of the week is it in New York?"}'
```

Poll `GET /api/agent/executions/{id}` until the status leaves `running`. Ordinary tasks stop at
30 active minutes (`AGENT_LIMITS_ACTIVEMILLIS`) or 5,000,000 charged tokens
(`AGENT_LIMITS_TASK_TOKEN_CEILING`). See [inference](inference.md) and
[agent runtime](agent-gateway.md).

## Enable optional integrations

Each integration is independent. Turning one on does not configure another.

| Integration | Main switches | Guide |
| --- | --- | --- |
| Slack conversations | `SLACK_ENABLED`, `SLACK_BOT_TOKEN`, `SLACK_APP_TOKEN` | [Slack](slack.md) |
| Scheduled tasks and monitoring | `AGENT_SCHEDULING_ENABLED=true` **and** `jobrunr.background-job-server.enabled=true` | [Scheduling and monitoring](scheduling-and-monitoring.md) |
| Browser tools and saved logins | `AGENT_BROWSER_ENABLED`, runtime install, `AGENT_SECRETS_KEY` | [Browser](browser.md) |
| Python code execution | `AGENT_CODE_EXECUTION_IMAGE`, `AGENT_CODE_EXECUTION_INSTALLATION_ID` | [Code execution](code-execution.md) |
| External MCP servers | `MCP_ENABLED`, `mcp.servers[n].*` in `.env` | [MCP connector](mcp.md) |
| Cloud continuation | `INFERENCE_CLOUD_FALLBACK_*` and a hosted API key | [Inference](inference.md#cloud-continuation) |

## Build a release JAR

```sh
sh ./mvnw -Pfrontend clean verify
java -jar velocity-modules/velocity-app/target/velocity-0.0.1-SNAPSHOT.jar
```

A JAR built without `-Pfrontend` contains no console assets. Run it from the repository root
so it finds `.env`, `data/` and `runtime/`.

## Next steps

- [Architecture](architecture.md) for the big picture.
- [Operator console](operator-console.md) for the screens and the frontend development loop.
- [Configuration reference](configuration.md) for every setting.
- [Development](development.md) for building, formatting and verification rules.
