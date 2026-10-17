# Web search

The `velocity-web-search` module gives agent tasks a `web_search` tool backed by the
[Tavily Search API](https://docs.tavily.com). It finds public pages for a query and returns ranked
results with titles, URLs and short excerpts, plus a brief synthesized answer when the provider
offers one. It never fetches a result page: reading still goes through the browser or research
tools and their destination policies.

- [Enabling](#enabling)
- [The tool](#the-tool)
- [Trust and limits](#trust-and-limits)
- [Status](#status)

## Enabling

```properties
WEB_SEARCH_ENABLED=true
TAVILY_API_KEY=<your key>
```

Startup fails when the feature is enabled without an API key, with a provider other than
`tavily`, or with a base URL that is not an `https://` origin. With the feature disabled nothing
is registered and the model never sees the tool.

| Property | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `web-search.enabled` | `WEB_SEARCH_ENABLED` | **`true`** | Registers `web_search`; startup fails without `TAVILY_API_KEY`, so set `WEB_SEARCH_ENABLED=false` when you have no key. |
| `web-search.provider` | `WEB_SEARCH_PROVIDER` | `tavily` | Only `tavily` is supported. |
| `web-search.api-key` | `TAVILY_API_KEY` | *empty* | Sent only as a bearer header to the base URL. |
| `web-search.base-url` | `WEB_SEARCH_BASE_URL` | `https://api.tavily.com` | No trailing slash. |
| `web-search.max-results` | `WEB_SEARCH_MAX_RESULTS` | `5` | Default count when the model gives none; 1–10. |
| `web-search.max-content-chars` | `WEB_SEARCH_MAX_CONTENT_CHARS` | `2000` | Excerpt length per result; 200–8000. |
| `web-search.request-timeout` | `WEB_SEARCH_REQUEST_TIMEOUT` | `20s` | Capped by the task's remaining time. |
| `web-search.search-depth` | `WEB_SEARCH_SEARCH_DEPTH` | `basic` | `basic` costs one Tavily credit, `advanced` two. |

## The tool

`web_search` takes `query` (required, ≤ 400 characters), `max_results` (1–10), `topic`
(`general` or `news`), `time_range` (`day`, `week`, `month`, `year`) and `include_domains` (up to
ten hostnames). It returns `status` (`ok`, `invalid_request`, `rate_limited`, `unavailable`),
`answer`, `results` (`title`, `url`, `content`, `score`, `published_date`), `result_count` and
`content_trust: untrusted_data`.

It is a trusted, cacheable `network.read` tool, so the executor may run up to four searches of one
response in parallel, and monitoring runs may use it. A lost connection is retried once; provider
rejections are reported in `status` without the upstream body, which can echo the query or the
key. Guidance tells the model to open the most relevant result before relying on details, to
record the URLs it used, and never to put credentials or document text into a query.

## Trust and limits

Search excerpts are untrusted third-party data. They cannot grant authority or change the task,
and they are stored like any other tool result (preview in the conversation, full output as an
execution artifact). Result URLs pass through the same sanitizer as other stored links, so a URL
carrying credentials is withheld. The module makes no other network calls.

## Status

`GET /api/operator/status` carries a `webSearch` section with `enabled`, `provider`,
`configured` and `maxResults`; the console shows it under Integrations & System.
