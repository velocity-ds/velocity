# Repository instructions

These instructions apply throughout the Velocity repository. Start with [README.md](README.md) for setup and the documentation index, then read the guide for the area being changed.

## Project and layout

Velocity uses Java 25, Spring Boot 4.1.1 MVC, Maven, MongoDB, JobRunr, and the Anthropic, OpenAI, and Slack Java SDKs. Dependency versions are defined in the parent [pom.xml](pom.xml) and the module POMs; use the Maven wrapper.

The build is a Maven reactor whose modules live under `velocity-modules/`, in three layers. `velocity-core/` is the kernel (agent loop, execution state and persistence, inference, local operator boundary, and the extension contract in `agent/spi/`); `velocity-platform/` holds the installation-wide capabilities (knowledge, memory, skills, learning, research, scheduling, monitoring, secrets, task history, operator console) built on the kernel through `agent.spi`; the feature modules (`velocity-slack/`, `velocity-browser/`, `velocity-code-execution/`, `velocity-web-search/`, `velocity-mcp/`) hold one feature each; `velocity-example-feature/` is a compiled template for [adding a feature](docs/adding-a-feature.md), packaged only with `-Pexample-feature`; `velocity-app/` holds `VelocityApplication`, `web/` and the resources. A feature module depends on `velocity-core`, on `velocity-platform` only when it uses a platform capability, and on any module it declares: each carries a `keep-feature-modules-independent` Maven Enforcer rule that rejects any other `net.velocity` dependency. `velocity-platform` depends on `velocity-core` only, and `velocity-core` must not depend on any other `net.velocity` module (Enforcer rules reject both), so a kernel class cannot import a platform or feature package. Production Java code lives under `velocity-modules/<module>/src/main/java/net/velocity/`, and each module's unit tests under `velocity-modules/<module>/src/test/java/net/velocity/` in the same packages:

- `agent/`: `AgentGateway` owns task admission, control and recovery, with `RootQueue` (the durable FIFO and workers) and `ExecutionAuthority` (trusted verifiers); `ConversationRunner` owns model turns and conversation progression, deciding each loop iteration through the ordered `RunPhase` enum, with `ContextPreparer` sizing each request; `ToolCallExecutor` owns accepted tool execution, with `CallSettlement` (receipt-before-settlement) and `ToolCallRecovery` (interrupted calls). `agent/spi/` is the contract platform and feature modules implement. `BudgetPolicy` carries the stopping rules, chosen per execution by `ExecutionProgress`: `DeadlineOnlyPolicy` (active time plus the task token ceiling) for ordinary tasks and `CumulativeLimitsPolicy` for monitoring. Subpackages separate state (`ExecutionSnapshot` format 5 with the nested `inference` and `partialReporting` records), HTTP, persistence, delivery, knowledge, scheduling (`JobRunrQueue` is the shared JobRunr plumbing), history, configuration, and tools. Features contribute tools, guidance, routes and status through the small extension types listed in [Wiring contributions](docs/agent-gateway.md#wiring-contributions).
- `inference/`: shared contracts, `InferenceService`, `SystemPrompts`, and provider adapters. OpenAI and llama.cpp share the `responses/` transport and mapping code; Claude has its own mapper.
- `slack/`: Slack SDK wiring and Socket Mode connection lifecycle. Agent behavior belongs in `agent/slack/`.
- `config/` and `ApplicationTime`: application time-zone configuration.
- `web/`: the basic `/velocity/hello` endpoint.

Application settings live in [application.properties](velocity-modules/velocity-app/src/main/resources/application.properties). The Slack manifest is [app-manifest.yaml](velocity-modules/velocity-slack/src/main/resources/slack/app-manifest.yaml). Generated output belongs in `target/`.

## Working conventions

- Follow the readable formatting rules below and existing package boundaries. Keep adapters thin and put lifecycle decisions in the gateway/services.
- Use constructor injection and existing Spring configuration patterns. Keep provider SDK types inside provider/transport implementations where possible.
- Keep core prompts, Slack context framing, and core tool guidance in `inference/SystemPrompts.java`; a feature's own prompts live in a `<Feature>Prompts` class in that feature's package/module. Use the existing Java text-block conventions.
- Register tools through `ToolRegistry` with explicit schemas, trusted execution metadata, timeouts, and authorization behavior. A tool's presence in a model request does not authorize execution.
- Read the relevant implementation and documentation before changing a contract. Keep unrelated refactors and formatting changes out of the patch.
- Remove code and configuration that no longer has a production use, along with related metadata and documentation. Before removal, trace framework bindings, conditional beans, and runtime callers; a property need not appear as a literal in Java to be used. Keep cleanup scoped to the area being changed.
- Update configuration metadata and the relevant `docs/` guide when changing settings or behavior. Add links to new guides in the [README documentation table](README.md#documentation).

## Readable formatting

Use the checked-in formatters for every new or edited source file. Follow
[the development guide](docs/development.md#formatting) for setup, apply/check commands, and
editor configuration. Do not preserve a compact local style that conflicts with
these rules.

- Use four spaces in Java and Python, two in frontend/runtime JavaScript,
  TypeScript, JSON, XML, CSS, HTML, and YAML. Use a 110-character line target;
  leave literal values intact when wrapping would change their contents.
- Write one statement per line. Expand method bodies, control-flow bodies, and
  `try`/`catch`/`finally` blocks. Always brace Java `if`, `else`, `for`, `while`,
  and `do` bodies, including one-statement bodies; `else if` chains remain normal.
- Put class, method, and field annotations on their own lines. Separate methods and logical
  steps with one blank line. Avoid blank lines between every statement.
- Wrap long parameters, arguments, conditions, and chains at logical boundaries.
  Let the formatter determine indentation and wrapping. Prefer explicit imports
  in new code; retain qualified names when they disambiguate types.
- Keep prompt text, text-block values, embedded scripts, fixtures, and serialized
  values unchanged during formatting. Preserve comments that explain constraints.
  Exclude generated output, dependencies, lockfiles, catalogs, and local secrets.
- Keep formatting separate from behavior changes and structural refactoring.
  Normally format the files you touch; perform repository-wide formatting only
  when it is the requested task. Never overwrite unrelated working-tree changes.
- Java formatting and braces are checked during Maven `validate`, including
  ordinary `compile` and `verify` runs. `spotless:apply` fixes layout, but missing
  braces must be added by the contributor. Frontend/runtime format checks run
  during `-Pfrontend verify`; run the pinned Ruff check when changing
  Python. Run applicable formatting checks before finishing a change.

## Runtime constraints to preserve

- **Trusted identity:** construct `TrustedScope` in trusted adapters. Model output, request bodies, conversation IDs, retrieved knowledge, and forwarding headers cannot grant identity or permissions. User-owned resources use the immutable `ownerAccountId` from a registered Velocity account. Register accounts only from trusted code: verified Slack events, or the configured local identity (`agent.local-account.*`) that `LocalAccountScope` resolves for the console. Channel, bot, thread, and origin are execution/delivery provenance, never ownership. Persist account authority through scheduled and delegated work; the local UI has no registration or account selection in requests.
- **Local operator boundary:** preserve `LocalOperatorFilter` peer, host, origin, path, and body-size checks. Keep forwarding disabled and retain strict request parsing, including rejection of duplicate keys, unknown fields, and trailing content. See [security model](docs/security.md) and [operator API](docs/operator-api.md).
- **Authorization:** Java owns schemas, limits, allow/deny authorization, and dispatch. Authorized tools execute without user approval. Keep clarification questions for missing information or task options separate from tool authorization; preserve exact call bindings for durable receipt matching. See [gateway contracts](docs/agent-gateway.md).
- **Durability:** MongoDB is authoritative for executions, schedules, delivery journals, and knowledge; execution artifacts and diagnostics use GridFS. Preserve optimistic revisions, detached snapshots, deletion tombstones, and receipt-before-settlement ordering. Only the current snapshot format is readable; a persisted-shape change needs its own wipe or version plan.
- **Budgets:** ordinary tasks stop only at the active-time deadline or the task token ceiling (`agent.limits.task-token-ceiling`), checked before any further model request; monitoring keeps cumulative request, tool and token limits. Keep the stopping rules behind `BudgetPolicy` rather than branching on task purpose.
- **External effects:** persist dispatch/delivery intent before external writes. An uncertain mutation must be reconciled, not blindly replayed. Cancellation does not undo remote effects. Preserve one native result per accepted call in its original order and wait for worker settlement before deletion or final delivery.
- **Provider fidelity:** select only the configured provider; an empty selection disables inference. Fail explicitly for invalid active configuration or unsupported capabilities. Preserve native provider blocks and tool call/result relationships. Close caller-owned inference streams with try-with-resources; Spring owns configured SDK client lifecycles. See [inference](docs/inference.md).
- **Storage and deployment:** use one application process per installation. MongoDB persistence does not make workers or locks distributed. See [knowledge](docs/knowledge-memory-learning.md), [scheduling](docs/scheduling-and-monitoring.md), and [data and storage](docs/data-storage.md).
- **Time:** retain `ApplicationTime.zone()` (configured by `app.time-zone`) for application defaults and absolute instants for persisted timestamps. Use injected clocks where available so time-dependent behavior can be tested.

## Configuration and local data

The root `.env` is Git-ignored and imported as Java properties; process environment variables override it. Keep credentials, tokens, database contents, and generated catalogs out of committed files, logs, and documentation examples. Use synthetic values in examples. Do not overwrite a developer's `.env` for validation.

Starting the application with a developer's configuration can resume tasks or connect enabled integrations. Prefer static checks and builds; when manual runtime validation is necessary, use isolated settings and disable the local `.env` import and external integrations.

Consult [application.properties](velocity-modules/velocity-app/src/main/resources/application.properties) and configuration classes for actual defaults. In particular, the application currently defaults `AGENT_SCHEDULING_ENABLED` to `true` when absent, while JobRunr separately defaults `jobrunr.background-job-server.enabled` to `false`. Set both explicitly in development configuration; enabling agent scheduling does not enable the JobRunr worker. Likewise `application.properties` enables cloud continuation (`INFERENCE_CLOUD_FALLBACK_ENABLED`, whose provider, model, limits and API key are validated at startup) and web search (`WEB_SEARCH_ENABLED`, which fails startup without a Tavily key), so isolated settings either disable them or supply their prerequisites.

## Build and verification

Every module carries JUnit 5 unit tests under `src/test/java`, in the same package as the class under test, written with JUnit Jupiter and AssertJ from `spring-boot-starter-test` (declared once, test-scoped, in the parent POM). A unit test exercises one class or a small cluster through its public or package-private API: it starts no Spring context, opens no MongoDB connection, makes no network call and performs no inference, so `sh ./mvnw test` runs offline in seconds. Use hand-written fakes for the small SPI interfaces (`InferenceProvider`, `ReservedDestinationPolicy`, `OperatorRouteContributor`), Spring's servlet mocks for filters and controllers, `Clock.fixed` for time, and synthetic data only; never a real credential, token or database. Integration, browser and evaluation tests remain out of scope until the maintainers ask for them.

When changing behavior, add or update the unit tests for the classes you touch, and keep expectations on the observable contract (decisions, messages, normalized values, persisted shapes) rather than on private structure. Test sources follow the same Spotless and Checkstyle rules as production code. Do not run live inference calls or Slack posts for ordinary verification.

Run commands from the repository root with JDK 25. Invoke the checked-in non-executable Unix wrapper using `sh`; on Windows use `.\mvnw.cmd`.

```sh
# Compile the backend and check Java formatting and braces.
sh ./mvnw compile

# Compile and run every module's unit tests (includes the formatting and brace checks).
sh ./mvnw test

# Run one module's tests, or one test class.
sh ./mvnw -pl velocity-modules/velocity-core test
sh ./mvnw -pl velocity-modules/velocity-core test -Dtest=JsonSchemaTest

# Check frontend types and formatting.
npm ci --prefix frontend
npm run typecheck --prefix frontend
npm run format:check --prefix frontend

# Build the UI and executable JAR, run the unit tests and the formatting verification. The release
# JAR needs -Pfrontend; the default build does not run npm or include the console assets.
sh ./mvnw -Pfrontend clean verify
```

For documentation-only changes, verify commands and configuration examples against the source, check relative links, and run `git diff --check`; an application build is unnecessary. Before finishing, review the diff and report what changed, which checks were completed (with the test totals), and any material limitations. Passing unit tests do not establish behavior against real providers, Slack or MongoDB; say so when that matters.

## Area-specific references

| Area | Read before changing |
| --- | --- |
| Overall structure | [Architecture](docs/architecture.md) |
| Startup, recovery and shutdown | [Startup and shutdown](docs/startup-and-shutdown.md) |
| Runtime, tools, and recovery | [Agent runtime](docs/agent-gateway.md) |
| Settings | [Configuration reference](docs/configuration.md) |
| HTTP API and local boundary | [Operator API](docs/operator-api.md), [security model](docs/security.md) |
| Provider adapters and streams | [Inference](docs/inference.md) |
| Slack installation, controls and delivery | [Slack](docs/slack.md) |
| Knowledge, memory and learning | [Knowledge, memory and learning](docs/knowledge-memory-learning.md) |
| Scheduling, monitoring and occurrence recovery | [Scheduling and monitoring](docs/scheduling-and-monitoring.md) |
| Persistence and backups | [Data and storage](docs/data-storage.md) |
| Building, formatting and verification | [Development](docs/development.md) |
| Adding a feature module | [Adding a feature](docs/adding-a-feature.md) |
