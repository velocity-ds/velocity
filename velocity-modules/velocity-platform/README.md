# velocity-platform

The platform layer: the capabilities every installation gets, built on the kernel in `velocity-core`
through the same extension points a feature module uses. Packages: `agent.knowledge` (stored
data), `agent.memory`, `agent.skills`, `agent.learning`, `agent.research` (downloads, document
reading, uploads, reports, output files), `agent.scheduling` (JobRunr-backed schedules),
`agent.monitoring`, `agent.secrets` (saved credentials), `agent.history` (task history and search)
and `operator` (status, health, playground). Each package carries its own `*Configuration`, tools
and operator controllers. Depends on `velocity-core` only; an Enforcer rule rejects any other
`net.velocity` dependency.

Guide: [docs/architecture.md](../../docs/architecture.md#module-structure).
