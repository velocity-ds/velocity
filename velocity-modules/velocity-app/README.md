# velocity-app

The runnable application: `VelocityApplication` (starts Spring; `ApplicationTimeInitializer` in
`velocity-core` applies `app.time-zone` while the environment loads), `web/` (`/velocity/hello` and console routes), `application.properties`,
`application-console-dev.properties` and the configuration metadata. Builds
`target/velocity-0.0.1-SNAPSHOT.jar`; `-Pfrontend` adds the console assets and `-Pexample-feature`
adds the example module. Run it from the repository root.

Guides: [startup and shutdown](../../docs/startup-and-shutdown.md),
[configuration](../../docs/configuration.md).
