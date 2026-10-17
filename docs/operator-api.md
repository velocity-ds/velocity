# Operator HTTP API

Every route Velocity serves, grouped by area. All `/api` routes sit behind the
[local operator boundary](security.md#local-operator-boundary): loopback peer, loopback `Host`,
matching `Origin`, 1 MiB body. Account-owned routes resolve the
[linked local account](getting-started.md#the-local-account) on each request and return
503 when it is missing.

- [Conventions](#conventions)
- [Public routes](#public-routes)
- [Tasks](#tasks)
- [Reports, code runs and delivery](#reports-code-runs-and-delivery)
- [Knowledge](#knowledge)
- [Memory, skills and learning](#memory-skills-and-learning)
- [Schedules](#schedules)
- [Monitoring](#monitoring)
- [Usage](#usage)
- [Inference playground](#inference-playground)
- [Status](#status)
- [Secrets](#secrets)
- [MCP connector](#mcp-connector)
- [Example feature](#example-feature)

## Conventions

- **Strict JSON.** Unknown fields, duplicate keys, wrong types and trailing content return 400.
  Routes reject query parameters and bodies they do not declare.
- **No identity in requests.** Owner, scope, provenance, tool names and filesystem paths are never
  accepted.
- **Status codes.** 400 invalid input, 403 not the owner, 404 missing or foreign record, 409
  revision or lifecycle conflict, 413 too large, 429 capacity, 503 unavailable dependency or
  missing linked account, 502 upstream failure.
- **Revisions.** Mutations of revisioned records take `expected_revision` (or `expectedRevision`
  for tasks). On 409, reload and decide again.
- **Lost responses.** Mutations carry an operation or request ID where an outcome could be
  uncertain. Reconcile with the matching read route; never resubmit blindly.
- **Field names.** Data adapters keep service field names (`snake_case`); task and playground
  DTOs use `camelCase`.

## Public routes

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/velocity/hello` | `Hello from Velocity!` — HTTP liveness only. |
| GET | `/`, `/tasks`, `/tasks/{id}`, `/knowledge`, `/secrets`, `/schedules`, `/monitoring`, `/playground`, `/system`, `/usage` | Forward to the console's `index.html`. |

## Tasks

| Method | Path | Body / query | Result |
| --- | --- | --- | --- |
| POST | `/api/agent/executions` | `{"request": "...", "conversationId"?: "...", "files"?: [{"filename", "mediaType"?, "contentBase64"}]}` | Snapshot. 503 without inference, 429 `agent_queue_full` with `Retry-After: 1`. Files (see below) are stored before the task is admitted and discarded if admission fails; 400 names the rejected file. |
| GET | `/api/agent/executions` | — | All snapshots (administrative). |
| GET | `/api/agent/executions/{id}` | — | Current snapshot; poll after mutations. |
| POST | `/api/agent/executions/{id}/updates` | `{"text": "..."}` | Steer an owned nonterminal task. |
| POST | `/api/agent/executions/{id}/answers` | `{"answers": ["..."], "expectedRevision"?: n}` | Answer a waiting owned task; 409 when the task waits for an approval instead. |
| POST | `/api/agent/executions/{id}/approvals` | `{"callId": "...", "approved": true\|false, "expectedRevision"?: n}` | 202. Decide one call held for [approval](agent-gateway.md#per-call-approval) on an owned waiting task; 409 when the call is not held. |
| GET | `/api/agent/executions/{id}/events` | — | `text/event-stream`; see [live task events](#live-task-events). |
| POST | `/api/agent/executions/{id}/continue` | `{"text"?: "..."}` | 202 with the new snapshot. Owned, settled `partial`/`failed`/`blocked`/`cancelled` task only (409 otherwise); repeats return the existing continuation. Files attached to the source task are copied into the continuation under new IDs. 503 without inference, 429 `agent_queue_full`. |
| POST | `/api/agent/executions/{id}/cancel` | — | 202. Administrative; never undoes remote effects. |
| GET | `/api/agent/executions/{id}/artifacts/{artifact}` | `offset` (default 0), `limit` (1–65,536, default 24,576) | Plain-text artifact chunk. |
| GET | `/api/operator/tasks/{id}/actions` | — | Advisory hints: `revision`, `owner`, `settled`, `steer`, `answer`, `approve`, `cancel`, `delete`, `continueTask`, and `continuation` (the existing continuation's ID or null). Mutations still recheck. |
| DELETE | `/api/operator/tasks/{id}` | `{"expectedUpdatedAt": "<exact original string>"}` | 204. Requires terminal and settled. |

`succeeded` means the response completed, not that the work is correct. Snapshots expose
`queueSequence` and `queuedAt`; a running task with `queuedAt` is waiting for a slot. Each call
in `calls` may carry `approval` (`state` `pending`, `approved` or `declined`, with
`requestedAt`, `decidedAt` and `decidedVia`) when the approval policy held it.

### Live task events

`GET /api/agent/executions/{id}/events` keeps a server-sent event stream open (at most 30
minutes; the browser reconnects) with a keep-alive comment every 15 seconds. Events:

| Event | Data | When |
| --- | --- | --- |
| `snapshot` | The execution snapshot, as `GET /api/agent/executions/{id}` returns it; `id` is its revision. | On subscription, then within about 150 ms of every saved change (changes are coalesced). |
| `delta` | `{"modelTurn": n, "text": "..."}` | Streamed assistant text of the model turn in flight; on subscription the text so far arrives as one delta. |
| `clear` | `{}` | The in-flight text was saved, discarded or retried; drop it. |
| `deleted` | `{}` | The execution was deleted; the stream then ends. |

The stream is advisory: the snapshot stays authoritative and the console keeps a slow poll as
a fallback. The route takes no body or query string and is protected like the other task
routes.

**Attached files.** `files` carries up to `agent.uploads.max-files` entries, each a simple ASCII
filename (≤ 100 characters, no paths) with strict Base64 content; `mediaType` is advisory and the
stored type is decided from the bytes and extension. Supported: TXT, LOG, MD, CSV, TSV, JSON, XML,
YAML, RTF, HTML, DOCX, XLSX, XLS, PDF, PNG, JPEG, within `agent.uploads.max-file-bytes` each and
`agent.uploads.max-total-bytes` together. The request body ceiling for this route grows
accordingly. The first user message lists the files as metadata only; the model reads them
through `task_file_list`, `task_file_open` and `document_read`, or passes a `file_id` to
`code_run` as an input artifact ([agent runtime](agent-gateway.md#core-tools)).

## Reports, code runs and delivery

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/operator/tasks/saved` | Tasks with saved outputs. |
| GET | `/api/operator/tasks/{id}/saved` | One task's saved outputs. |
| GET | `/api/operator/tasks/{id}/reports` | Report and output file (`task_attach_file`, `browser_screenshot`) metadata with Slack delivery state. |
| GET | `/api/operator/tasks/{id}/reports/{reportId}/download` | Attachment download of a report or output file, `nosniff`, `no-store`. |
| GET | `/api/operator/tasks/{id}/files` | Files attached to the task request, each with the document it was extracted into and that extraction's status when `task_file_open` has run. |
| GET | `/api/operator/tasks/{id}/files/{fileId}/download` | Original bytes as an `application/octet-stream` attachment, `nosniff`, `no-store`; never rendered inline. |
| GET | `/api/operator/tasks/{id}/code-runs` | Code run receipts, stdout/stderr, state. |
| GET | `/api/operator/tasks/{id}/artifacts` | Generated code artifacts. |
| GET | `/api/operator/tasks/{id}/artifacts/{artifactId}/download` | Binary download. |
| GET | `/api/operator/tasks/{id}/delivery` | Delivery acknowledgement state used by learning. |
| POST | `/api/operator/tasks/{id}/acknowledge` | Record a local delivery acknowledgement for learning. |
| POST | `/api/operator/tasks/{id}/learn` | Queue a user-requested skill review of a settled task (`name`, `focus` optional). |

## Knowledge

| Method | Path | Body |
| --- | --- | --- |
| POST | `/api/operator/knowledge/catalog` | `mode?` (`records`/`datasets`), `dataset?`, `search?`, `offset?`, `limit?`, `after?` |
| POST | `/api/operator/knowledge/query` | `dataset`, `filters?`, `fields?`, `sort_by?`, `descending?`, `offset?`, `limit?` |
| POST | `/api/operator/knowledge/aggregate` | `dataset`, `operation` (`count`/`sum`/`avg`/`min`/`max`), `filters?`, `field?` (`content.<path>`, required except for `count`), `group_by?`, `group_limit?` (≤ 100) |
| POST | `/api/operator/knowledge/get` | `id`, or `dataset` + `key`; `expected_revision?`, `offset?`, `limit?` (≤ 4,000) |
| POST | `/api/operator/knowledge/save` | `dataset`, `key`, `title`, `description`, `tags`, `content`, `expected_revision` (0 creates) |
| POST | `/api/operator/knowledge/update` | `id`, or `dataset` + `key`; `expected_revision`; `set?`, `append?`, `unset?`, `title?`, `description?`, `tags?` |
| POST | `/api/operator/knowledge/delete` | `id`, or `dataset` + `key`; `expected_revision` → `{"status":"deleted", ...}` |

These mirror the `data_*` tools; see [knowledge](knowledge-memory-learning.md#knowledge). Operator
writes run directly: the confirmation step applies to tool calls made by the agent, where the
operator is the one who confirms.

## Memory, skills and learning

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/operator/memory/inspect` | Read user or environment facts and proposals. |
| POST | `/api/operator/memory/update` | Add, replace or remove a fact (request ID + revision). |
| POST | `/api/operator/memory/control` | Protect, unprotect or reset a store. |
| POST | `/api/operator/memory/proposal` | Accept or reject a pending proposal. |
| POST | `/api/operator/skills/bundles` | List bundled skills available to install. |
| POST | `/api/operator/skills/install` | Install a bundled skill by `bundle_id`. |
| POST | `/api/operator/skills/list`, `/load`, `/reference` | Read skills. |
| POST | `/api/operator/skills/create`, `/patch`, `/control` | Change skills. |
| POST | `/api/operator/skills/proposal` | Apply (disable the skill) or reject a curator retirement proposal (`proposal_id`, `apply`, request ID). |
| GET | `/api/operator/learning` | Learning review status, outcomes and failure details. |
| GET | `/api/operator/learning/journey` | Learning journey: saved facts, skill versions and committing reviews, newest first (at most 200). |

## Schedules

| Method | Path | Body |
| --- | --- | --- |
| GET | `/api/operator/schedules` | — |
| GET | `/api/operator/schedules/{id}` | — (includes prompt and up to 20 occurrences) |
| POST | `/api/operator/schedules` | `name`, `prompt`, `timing` → 201 |
| PATCH | `/api/operator/schedules/{id}` | `expected_revision` plus any of `name`, `prompt`, `timing` |
| POST | `/api/operator/schedules/{id}/pause`, `/resume`, `/cancel` | `{"expected_revision": n}` |

`timing` is exactly one of `at` (ISO timestamp), `delay_seconds` or `cron` (Spring six-field),
plus optional IANA `timezone` (default: the application zone, `app.time-zone`). The local API creates agent-prompt
schedules only; Slack message schedules need Slack destination authority.

## Monitoring

| Method | Path | Body / query |
| --- | --- | --- |
| GET | `/api/operator/monitoring` | `offset`, `limit` (≤ 100) |
| POST | `/api/operator/monitoring` | `name`, `original_request`, `objective`; optional `criteria`, `exclusions`, `sources`, `notification_instructions`, `completion_condition`, `completion_rule`, `timing`, `notification_policy`, `digest_timing`, `initial_summary`, `end_at` |
| GET | `/api/operator/monitoring/{id}` | — |
| PATCH | `/api/operator/monitoring/{id}` | `expected_revision` plus fields to change |
| POST | `/api/operator/monitoring/{id}/pause`, `/resume`, `/run-now`, `/close` | `{"expected_revision": n}` |
| GET | `/api/operator/monitoring/{id}/runs`, `/observations`, `/updates` | `offset`, `limit` |

## Usage

| Method | Path | Query |
| --- | --- | --- |
| GET | `/api/operator/usage/summary` | — |
| GET | `/api/operator/usage` | `from`, `to` (≤ 366 days); optional `provider`, `model`, `activity` |
| GET | `/api/operator/tasks/{id}/usage` | — |
| GET | `/api/operator/usage/tasks` | `ids` (≤ 100) |

See [usage and costs](usage-costs.md).

## Inference playground

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/operator/inference` | Provider, model, capabilities, presets, limits. |
| POST | `/api/operator/inference` | One complete response. 503 without inference. |
| POST | `/api/operator/inference/stream` | Same request as SSE: `text_delta`, `thinking_delta`, `citation`, `complete`, `error`. |
| DELETE | `/api/operator/inference/sessions/{id}` | Discard a session; 204. |

Request fields: `prompt` (≤ 100,000 chars), `sessionId` + `expectedRevision` to continue,
`systemPrompt` (≤ 30,000) or `preset`, up to four `images` (`{base64, mediaType}`),
`outputSchema`, `webSearch`, `adaptiveThinking`, `computerUse`. Sessions are in memory: at most
16, 8 MiB of history each, 30-minute idle expiry, cleared on restart. Computer-use output is a
proposal only; nothing is executed.

## Status

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/operator/status` | Safe effective configuration, one section per `SystemStatusContributor`. No network probes. |
| GET | `/api/operator/status/mongo` | Performs a real MongoDB ping; returns `connected`/`unavailable` and `checkedAt`. |
| GET | `/api/operator/status/concurrency` | Active and queued roots, provider pool usage and limits. |
| GET | `/api/operator/actuator/health`, `/info`, `/metrics`, `/prometheus` | Spring Boot Actuator under the protected root; see [observability](observability.md). |

Status never includes credentials, URIs, `.env` contents or exception messages. "Enabled" and
"configured" are not "connected".

## Secrets

Sensitive root; `no-store`; bodies ≤ 32,768 characters.

| Method | Path | Body |
| --- | --- | --- |
| GET | `/api/operator/secrets` | optional `search` |
| GET | `/api/operator/secrets/{id}` | — (metadata only, never values) |
| POST | `/api/operator/secrets` | `request_id`, `service`, `account_label`, `credential_origins`, `post_login_origins`, `username`, `password`; optional `application_origins`, `callback_urls`, `resource_origins` |
| PUT | `/api/operator/secrets/{id}` | metadata + `request_id`, `expected_revision`; optional new `username` + `password` pair |
| POST | `/api/operator/secrets/{id}/state` | `request_id`, `expected_revision`, `enabled` |
| DELETE | `/api/operator/secrets/{id}` | `request_id`, `expected_revision` |
| GET | `/api/operator/secrets/operations/{requestId}` | Committed receipt or `not_found` |

## MCP connector

`GET /api/operator/mcp` → `{"enabled": ..., "confirmWrites": ..., "servers": [{"name": ..., "transport": ..., "state": ..., "tools": [...], "skippedTools": ..., "missingTools": [...]}]}`.
No query or body; `no-store`. Configured names, derived tool names, states and counts only, never a
server's own text. See [MCP connector](mcp.md#status-and-api).

## Example feature

Present only in builds packaged with `-Pexample-feature`:
`GET /api/operator/example` → `{"enabled": ..., "tool": "example_echo"}`.
