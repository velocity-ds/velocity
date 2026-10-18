# Development

How to build, format and verify changes. Repository rules for contributors (human and agent)
are in [AGENTS.md](../AGENTS.md).

- [Toolchain](#toolchain)
- [Build commands](#build-commands)
- [Formatting](#formatting)
- [Verification policy](#verification-policy)
- [Frontend development](#frontend-development)
- [Working on the runtime](#working-on-the-runtime)

## Toolchain

| Tool | Version / source |
| --- | --- |
| JDK | 25 |
| Maven | checked-in wrapper (`sh ./mvnw`, `.\mvnw.cmd`) |
| Spring Boot | 4.1.1 (parent POM) |
| Node.js / npm | 22.12+ for the console and the browser runtime |
| Prettier | 3.6.2, pinned in `frontend/package.json` |
| Spotless + Eclipse JDT 4.37 | Java and POM formatting, `config/eclipse-formatter.xml` |
| Checkstyle 14.3.0 | required braces, `config/checkstyle.xml` |
| JUnit 5, AssertJ, Spring test mocks | `spring-boot-starter-test` (test scope, parent POM); run by Surefire |
| Ruff | pinned in `requirements-dev.txt`, configured in `ruff.toml` |

The Unix wrapper is committed without the executable bit; run it through `sh`.

## Build commands

```sh
# Compile every module and check Java formatting and braces (no Node needed).
sh ./mvnw compile

# Compile and run every module's unit tests.
sh ./mvnw test

# One module, or one test class.
sh ./mvnw -pl velocity-modules/velocity-platform test
sh ./mvnw -pl velocity-modules/velocity-platform test -Dtest=ScheduleTimingTest

# Full release build: console, JAR, unit tests, all formatting checks.
sh ./mvnw -Pfrontend clean verify

# Include the example feature module in the JAR.
sh ./mvnw -Pexample-feature package

# Frontend only.
npm ci --prefix frontend
npm run typecheck --prefix frontend
npm run format:check --prefix frontend
npm run build --prefix frontend
```

The JAR is `velocity-modules/velocity-app/target/velocity-0.0.1-SNAPSHOT.jar`. Without `-Pfrontend` it has no console.

```mermaid
flowchart LR
  v[validate<br/>Spotless check, Checkstyle,<br/>Enforcer] --> gr[generate-resources<br/>-Pfrontend: npm ci, npm run build]
  gr --> c[compile]
  c --> pc[process-classes<br/>-Pfrontend: copy target/frontend → static/]
  pc --> t[test<br/>Surefire: JUnit 5 unit tests]
  t --> p[package<br/>Spring Boot JAR]
  p --> vf[verify<br/>-Pfrontend: Prettier checks for<br/>frontend + browser runtime]
```

## Formatting

Rules (also in [AGENTS.md](../AGENTS.md#readable-formatting)): 110-character target; four spaces
for Java and Python, two for JS/TS/JSON/XML/CSS/HTML/YAML; one statement per line; braces on every
Java `if`/`else`/`for`/`while`/`do`; annotations on their own lines; never reflow prompt text, text
blocks, embedded scripts or fixtures. `.editorconfig` and `.gitattributes` (LF) back this up.

```sh
# Java and POMs
sh ./mvnw spotless:apply
sh ./mvnw validate
sh ./mvnw spotless:apply "-DspotlessFiles=.*InferenceService\.java"   # regex on absolute path

# JavaScript / TypeScript
npm run format --prefix frontend
npm run format --prefix runtime/browser-use-runtime

# Python
python -m venv target/formatting-tools
target/formatting-tools/bin/python -m pip install -r requirements-dev.txt
target/formatting-tools/bin/python -m ruff format runtime/browser-use-runtime runtime/code-execution-runtime
```

Spotless fixes layout; add missing braces yourself. Both cover `src/test/java` as well as
`src/main/java`. The runtime formatting scripts reuse the frontend's Prettier install. `runtime/browser-use-runtime/rendered_content.js` is deliberately
excluded from Prettier — keep its layout.
Format only files you touch, and keep formatting separate from behavior changes.

## Verification policy

Every module carries JUnit 5 unit tests under `src/test/java`, run by Surefire during
`sh ./mvnw test` and every `package` or `verify` build. A test class sits in the same package as
the class it covers (so package-private contracts such as `RunPhase`, `CallBindings` or
`SlackTableBlocks` are reachable) and uses JUnit Jupiter and AssertJ from
`spring-boot-starter-test`, which the parent POM declares once with test scope. Spring's servlet
mocks (`MockHttpServletRequest`, `MockFilterChain`) serve filter and controller tests.

Unit tests are offline and self-contained: no Spring context, MongoDB, network, Slack or
inference. Fake the small SPI interfaces by hand (`InferenceProvider`, `ReservedDestinationPolicy`,
`OperatorRouteContributor`), fix time with `Clock.fixed`, and use synthetic data; a test never
carries a real credential. Prefer destinations and addresses that resolve without DNS (IP
literals, configured private origins) so the suite is deterministic everywhere.

What to cover: the decision and validation logic that the runtime constraints in
[AGENTS.md](../AGENTS.md#runtime-constraints-to-preserve) depend on, such as schema and strict
request parsing, the local operator boundary, path and destination policies, budgets and run
phases, schedule timing, fingerprints, result parsers and redaction. Assert the observable contract
(the decision, the message, the normalized value, the persisted shape), not private structure.
Integration, browser and evaluation tests stay out of scope until the maintainers ask for them;
`-Pfrontend verify` runs the frontend's type and format checks, not frontend tests.

Validate changes with:

1. `sh ./mvnw test` (formatting, braces, Enforcer rules, compilation, unit tests);
   `sh ./mvnw compile` alone while iterating on layout.
2. Frontend typecheck and format check when `frontend/` changes.
3. Ruff check when Python changes.
4. `sh ./mvnw -Pfrontend clean verify` for packaging changes.
5. For documentation-only changes: check commands and settings against source, check relative
   links, and run `git diff --check`.

Report what you ran, including the test totals. Passing unit tests do not establish behavior
against real providers, Slack or MongoDB. Do not run live inference or Slack posts for ordinary
verification. If a runtime check is warranted, use a disposable database and the
[isolated settings](startup-and-shutdown.md#safe-isolated-starts), and never overwrite a
developer's `.env`.

## Frontend development

See [operator console — development loops](operator-console.md#development-loops) for Vite with
hot reload and the `console-dev` watched-build profile.

## Working on the runtime

Before changing an area, read its guide:

| Area | Guide |
| --- | --- |
| Gateway, runner, tools, recovery | [agent runtime](agent-gateway.md) |
| Startup, lifecycle, recovery order | [startup and shutdown](startup-and-shutdown.md) |
| Settings | [configuration](configuration.md) |
| Providers and streams | [inference](inference.md) |
| HTTP routes | [operator API](operator-api.md), [security](security.md) |
| Persistence | [data and storage](data-storage.md) |
| New module | [adding a feature](adding-a-feature.md) |

When you change a setting or behavior, update `application.properties`, the configuration
metadata and the matching guide in the same change.
