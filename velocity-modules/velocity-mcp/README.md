# velocity-mcp

Feature module (`mcp` package): connects operator-configured external
[Model Context Protocol](https://modelcontextprotocol.io) servers over stdio or Streamable HTTP
and registers each discovered tool as a Velocity tool named `mcp_<server>_<tool>`, with its
guidance, the `mcp` status section and `GET /api/operator/mcp`. Active only with
`MCP_ENABLED=true` and at least one `mcp.servers[n]` entry.

Guide: [docs/mcp.md](../../docs/mcp.md).
