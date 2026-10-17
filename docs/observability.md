# Observability

Velocity exposes Spring Boot Actuator health and Micrometer metrics under the protected operator
root, tags every task log line with its execution ID, and keeps the operator status document
for configuration facts. Nothing here makes a provider or Slack call.

- [Endpoints](#endpoints)
- [Health](#health)
- [Metrics](#metrics)
- [Logs](#logs)
- [Settings](#settings)

## Endpoints

Actuator is mounted at `/api/operator/actuator`, inside the `/api/operator` root, so
`LocalOperatorFilter` applies the same loopback peer, Host and Origin checks as the console
([security model](security.md#local-operator-boundary)). A scraper or probe must run on the
same machine and send a loopback `Host`.

| Path | Content |
| --- | --- |
| `GET /api/operator/actuator/health` | Aggregate status with components (details are shown because readers are local operators). |
| `GET /api/operator/actuator/health/{component}` | One component, for example `mongo`, `inference`, `agentGateway`, `slack`. |
| `GET /api/operator/actuator/info` | Build information when present. |
| `GET /api/operator/actuator/metrics` and `/metrics/{name}` | Micrometer meters, with tag drill-down. |
| `GET /api/operator/actuator/prometheus` | Prometheus text exposition of the same meters. |

`GET /api/operator/status` remains the place for effective configuration
([operator API](operator-api.md#status)); health is about readiness, status about settings.

## Health

| Component | Source | Meaning |
| --- | --- | --- |
| `mongo` | Spring Boot | A real `ping` against the configured database. |
| `inference` | core | UP with the selected provider; UNKNOWN when no provider is configured, which is valid. |
| `agentGateway` | core | UP while the gateway admits work, with active and queued root counts; DOWN once shutdown drains. |
| `slack` | Slack module | UNKNOWN while disabled, UP while the Socket Mode lifecycle runs, DOWN when enabled but stopped. |

Health never includes credentials, hosts, URIs or exception messages.

## Metrics

Meters are recorded on Micrometer's global registry with tags drawn from bounded vocabularies
(tool and provider names, statuses, outcome codes), never user text or identifiers. Every meter
carries `application=<spring.application.name>`.

| Meter | Type | Tags | Recorded when |
| --- | --- | --- | --- |
| `velocity.tasks.admitted` | counter | `origin`, `monitoring` | A root task is persisted at admission. |
| `velocity.tasks.stopped` | counter | `status`, `root` | A run reaches a terminal status (a stop boundary or a completed response); tasks expired while queued are not counted. |
| `velocity.tasks.active`, `velocity.tasks.queued` | gauge | | Root workers running, roots waiting for a slot. |
| `velocity.tool.calls` | counter | `tool`, `outcome`, `root` | A tool outcome is published, including denied, declined and uncertain outcomes. |
| `velocity.tool.duration` | timer | `tool` | Dispatch to published outcome. |
| `velocity.inference.requests` | timer | `provider`, `model`, `outcome` | Each provider request: `completed`, `streamed`, `rejected`, a transient failure code, `failed`, or `stream_closed` for a stream closed before completion. |
| `velocity.inference.tokens` | counter | `provider`, `model`, `kind` | Usage of completed requests by `input`, `output`, `cache_read` and `cache_creation` tokens. |

The usage ledger ([usage and costs](usage-costs.md)) remains the durable, per-account record;
metrics are process-local and reset on restart. JVM, HTTP server and MongoDB driver meters come
from Spring Boot's auto-configuration.

## Logs

Worker and tool-handler log lines carry `executionId` (and `tool` for handlers) through the
logging context; `logging.pattern.level` prints it as `[executionId=…]` on every line, empty
outside task work. The existing `stage=… outcome=…` key-value style is unchanged.

## Settings

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `management.endpoints.web.base-path` | `MANAGEMENT_ENDPOINTS_WEB_BASEPATH` | `/api/operator/actuator` | Keep it under a protected root; the route audit does not cover actuator mappings. |
| `management.endpoints.web.exposure.include` | `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info,metrics,prometheus` | |
| `management.endpoint.health.show-details` | `MANAGEMENT_ENDPOINT_HEALTH_SHOWDETAILS` | `always` | |
| `management.endpoint.health.show-components` | `MANAGEMENT_ENDPOINT_HEALTH_SHOWCOMPONENTS` | `always` | |
