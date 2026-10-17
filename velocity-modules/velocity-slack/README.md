# velocity-slack

Slack Socket Mode connection (`slack`) and agent ingress and delivery (`agent.slack`). Active only
when `slack.enabled=true`. Ships the Slack app manifest at
`src/main/resources/slack/app-manifest.yaml`. Depends on `velocity-core` and the Slack SDK (with
Tyrus, which must precede Tomcat's WebSocket API on the classpath).

Guide: [docs/slack.md](../../docs/slack.md).
