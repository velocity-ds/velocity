# Contributing to Velocity

Thanks for helping. This page is the short version; the repository rules that every change must
follow, including the runtime constraints reviewers check, are in [AGENTS.md](AGENTS.md), and the
build, formatting and verification details are in [docs/development.md](docs/development.md).

## Before you start

- Read [docs/architecture.md](docs/architecture.md) once, then the guide for the area you are
  changing (the table at the end of [AGENTS.md](AGENTS.md) maps areas to guides).
- Open an issue for anything larger than a bug fix or a documentation correction, so the design
  can be discussed before the code exists.
- Keep a change to one concern. Formatting-only and refactoring-only changes go in their own
  pull requests.

## Building and checking a change

Run from the repository root with JDK 25 (Node.js 22.12+ when the console changes):

```sh
sh ./mvnw compile                      # Java compilation, Spotless and Checkstyle
npm ci --prefix frontend && npm run typecheck --prefix frontend && npm run format:check --prefix frontend
sh ./mvnw -Pfrontend clean verify      # the full release build, before opening a pull request
```

On Windows use `.\mvnw.cmd`. `sh ./mvnw spotless:apply` and `npm run format --prefix frontend`
fix layout; missing braces must be added by hand.

Automated tests are paused in this repository (see
[the verification policy](docs/development.md#verification-policy)); do not add test code
until the maintainers reopen it. Describe in the pull request what you ran and what it does and
does not establish.

## Pull requests

- Explain the behavior change and name the guide you updated. A change to a setting updates
  `application.properties`, the configuration metadata and the configuration reference together.
- Never commit credentials, tokens, database contents or a real `.env`; examples use synthetic
  values.
- A change to a persisted shape needs its own wipe or version plan
  ([data and storage](docs/data-storage.md)).

## License

By contributing you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE) that covers the project.
