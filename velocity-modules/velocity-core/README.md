# velocity-core

The kernel: the agent loop, execution state and persistence, inference, the local operator
boundary and the extension contract every other module implements. It must not depend on any
other `net.velocity` module, and it may not pull in POI, PDFBox or the Slack SDK (Maven Enforcer
rules). Installation-wide capabilities live in [`velocity-platform`](../velocity-platform/README.md).

| Package (`net.velocity.…`) | Contents |
| --- | --- |
| `agent` | `AgentGateway` with `RootQueue` and `ExecutionAuthority`; `ConversationRunner` with `ContextPreparer`; `ToolCallExecutor` with `CallSettlement` and `ToolCallRecovery`; `DelegationService`; budgets; `ToolRegistry`, `AuthorizationPolicy`, `ContextHooks`. |
| `agent.spi` | The contract platform and feature modules implement: `ToolProvider`, `ContextContributor`, `GatewayCustomizer`, `AuthorizationPolicyDecorator`, `DelegationProfile`, `OperatorRouteContributor`, `ReservedDestinationPolicy`, `SystemStatusContributor`, `UsageActivityContributor`, `OutputFileSource`. |
| `agent.state`, `agent.persistence` | Execution snapshot (format 5) and its MongoDB repository. |
| `agent.delegation`, `agent.delivery`, `agent.accounts` | Child-run hooks and reference validation, delivery journals, registered accounts. |
| `agent.http`, `agent.security` | Task, task-action, live-event and usage controllers, `LocalOperatorFilter`, `LocalAccountScope`, strict request parsing, protected routes and route audit, destination policies. |
| `agent.config`, `agent.tools` | Kernel wiring and properties; kernel tools (`ask_user`, `datetime_now`, `task_evidence_read`, `delegate_work`) and the shared `ToolSchemas`. |
| `inference` | `InferenceService`, profiles, limiter, `SystemPrompts`, Claude / OpenAI / llama.cpp providers. |
| `usage` | Usage ledger, pricing and reports. |

Guides: [architecture](../../docs/architecture.md), [agent runtime](../../docs/agent-gateway.md),
[inference](../../docs/inference.md), [startup and shutdown](../../docs/startup-and-shutdown.md).
