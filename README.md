# mcp
![Build](https://github.com/trevorism/mcp/actions/workflows/deploy.yml/badge.svg)
![GitHub last commit](https://img.shields.io/github/last-commit/trevorism/mcp)
![GitHub language count](https://img.shields.io/github/languages/count/trevorism/mcp)
![GitHub top language](https://img.shields.io/github/languages/top/trevorism/mcp)

The **Trevorism MCP control plane** — an on-platform Micronaut/Groovy service that exposes the whole
Trevorism platform to AI agents as [Model Context Protocol](https://modelcontextprotocol.io) tools over
**Streamable HTTP** at `https://mcp.project.trevorism.com/mcp`.

## Client setup

Mint a user refresh token, then register the server. In Claude Code:

```powershell
$token = Get-TrevorismRefreshToken // refresh token preferred since it lasts ~1 day instead of 15 minutes
claude mcp add --transport http trevorism https://mcp.project.trevorism.com/mcp --header "Authorization: Bearer $token"
```

## Architecture

- **ServiceRegistry** — enumerates services from the unsecured `active` endpoint, resolves each to its
  canonical host via the `project` service's category (`dns`) + platform naming convention, validated by
  `/ping`. Cached in-memory (~1h TTL).
- **SpecHarvester** — fetches each service's `/help` → OpenAPI YAML and summarizes it for `describe_service`.
- **TokenManager** — redeems + caches per-user access tokens from the caller's refresh token.
- **McpController** — hand-rolled JSON-RPC 2.0 (`initialize`, `tools/list`, `tools/call`) over
  Micronaut/Netty; **PassThroughClient** forwards the resolved access token downstream.

## Build, test, deploy

```bash
gradle clean build       # compile + unit tests (JUnit5) + jacoco
gradle acceptance        # cucumber acceptance tests against the deployed instance
```

Deploys to GCP App Engine on push to `master` (`.github/workflows/deploy.yml`), which also runs the
acceptance suite (`accept.yml`) against the fresh deploy.
