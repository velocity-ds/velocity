# Operator console

The console is a React 19 + TypeScript + Vite single-page app in `frontend/`, served by Spring
Boot from the same origin as the API (`http://localhost:8090/`). It uses relative API URLs and
the same [local operator boundary](security.md#local-operator-boundary) as every `/api` route.

- [Screens](#screens)
- [Building](#building)
- [Development loops](#development-loops)
- [How the console talks to the server](#how-the-console-talks-to-the-server)
- [Display rules](#display-rules)

## Screens

| Screen | Route | What it does |
| --- | --- | --- |
| Overview | `/` | Task activity and waiting states, recent results and failures, integration availability, today's and this month's estimated spend. |
| Tasks | `/tasks`, `/tasks/{id}` | Create (optionally attaching files), inspect, filter, steer, answer, cancel, continue and delete tasks. **Continue** on a stopped task starts a linked new task, with an optional extra instruction. Details show tool activity, browser observations, provider/model, context diagnostics, usage and cost, limits, artifacts, attached files, saved reports, **Code & files**, and the model-reported outcome. |
| AI Usage | `/usage` | Estimated spend by date range (≤ 366 days), provider, model and activity, with pricing snapshots and coverage. |
| Knowledge | `/knowledge` | Datasets, Facts and Skills for the linked account; catalog rebuild; bundled skill install. |
| Secrets | `/secrets` | Saved website logins: create, edit metadata, replace pair, enable/disable, delete. Values are never read back. |
| Schedules | `/schedules` | Account schedules, prompt schedule creation, revision-checked edits, pause/resume/cancel, recent occurrences. |
| Monitoring | `/monitoring` | Monitoring jobs, runs, observations and updates; create, edit, pause, resume, run now, close. |
| Inference Playground | `/playground` | Direct requests to the default provider with capability-driven options, streaming, images and structured output. |
| Integrations & System | `/system` | Safe effective configuration, MongoDB check, code execution status, web search and MCP connector state. |

Task creation, steering, answers and all account-owned screens act as the
[linked local account](getting-started.md#the-local-account). The console can answer and
steer that account's Slack tasks too; replies still go to their original Slack thread.

## Building

The console is not part of the default Maven build. The `frontend` profile builds it:

```sh
sh ./mvnw -Pfrontend clean verify     # npm ci, tsc, vite build, package into the JAR
```

Vite writes to `target/frontend/`; Maven copies that into `velocity-modules/velocity-app/target/classes/static/` after
compilation, so incremental Java builds never delete the UI. Never copy built assets into
`src/main/resources`.

Standalone frontend checks:

```sh
npm ci --prefix frontend
npm run typecheck --prefix frontend
npm run format:check --prefix frontend
npm run build --prefix frontend
```

On Windows, stop any running Vite server or build watcher before `npm ci` or a `-Pfrontend`
build; a running esbuild process can lock `node_modules` and cause `EPERM ... unlink`.

## Development loops

**One command on Windows.** `scripts\start-dev.cmd` (or `powershell -ExecutionPolicy Bypass -File
scripts\start-dev.ps1`) stops whatever listens on the backend port (`SERVER_PORT`, default 8090)
and on 5173, then starts `spring-boot:run` for `velocity-app` and the Vite dev server, each in its own
console window, and waits until both answer. Run it again to restart both.

**Vite with hot reload.** Start Spring as usual, then:

```sh
npm run dev --prefix frontend
```

Open `http://localhost:5173/`. Vite binds to `127.0.0.1:5173` and proxies `/api/` and
`/velocity/hello` to `127.0.0.1:8090`, preserving the browser's `Host` and `Origin` so Spring's
checks still apply. It adds no forwarding headers and no CORS. Its middleware also rejects
non-loopback callers and duplicate boundary headers, and refuses chunked DELETE/OPTIONS bodies
(Vite's proxy would drop them). For a different backend port:

```sh
VELOCITY_DEV_BACKEND_PORT=8091 npm run dev --prefix frontend
```

**Watched build served by Spring.**

```sh
npm run build:watch --prefix frontend
sh ./mvnw -Dspring-boot.run.profiles=console-dev -pl velocity-modules/velocity-app -am spring-boot:run
```

The `console-dev` profile serves `target/frontend/` directly. Reload the page after changes and
run `npm run typecheck` separately (the watcher does not type-check). Do not run `mvnw clean`
while watching.

Use one exact origin throughout: `localhost` and `127.0.0.1` are different origins.

## How the console talks to the server

- Tasks are **polled**; there is no task push channel. The client keeps the newest revision
  when responses arrive out of order.
- Action buttons follow `GET /api/operator/tasks/{id}/actions`, matched to the snapshot
  revision. Hints are advisory; the server rechecks every mutation.
- Delete sends the exact original `updatedAt` string (not round-tripped through `Date`, which
  loses sub-millisecond precision).
- Mutations that can be uncertain (secrets)
  keep their operation ID and reconcile through a read route instead of posting again.
- Secret form values live only in transient form state; they never enter URLs, storage, query
  caches or notifications. Only a pending secret operation's request ID is kept in session
  storage.
- Email content and short-lived sign-in state stay in memory.

See the full route list in [operator API](operator-api.md).

## Display rules

- `succeeded` is shown as **Response complete**: the model finished, not "verified correct".
  The model-reported outcome is shown separately, or **Not reported**.
- Unknown values show **Unavailable**, never zero. Costs distinguish complete, partial,
  unpriced and unavailable estimates. Decimal amounts keep all server digits.
- Model output, Slack text, web content, email and knowledge are rendered as escaped,
  untrusted content. Files are never rendered inline.
- Times display in the application zone that `/api/operator/status` reports (the browser's zone
  until it loads) with an explicit offset; date-only values stay dates.
- Navigation and the light/dark preference persist in the browser; filters are URL-backed.
