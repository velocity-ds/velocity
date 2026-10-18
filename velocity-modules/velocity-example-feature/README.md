# velocity-example-feature

Compiled template for a new feature module (`example` package): one read-only `example_echo`
tool, its guidance, a status section, `GET /api/operator/example` and a unit test
(`ExampleToolsTest`) of the tool's metadata, schemas and handler. Built and tested by every full
reactor build; packaged into the JAR only with `-Pexample-feature`; active only with
`EXAMPLE_ENABLED=true`.

Guide: [docs/adding-a-feature.md](../../docs/adding-a-feature.md).
