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
- **CloudLoggingClient** — reads Cloud Logging entries for `read_gcloud_logs` (see below).

## Reading Google Cloud logs

`read_gcloud_logs` lets an agent see why a service is misbehaving. Give it a Trevorism service name
(`{"service": "event", "severity": "ERROR", "since": "2h"}`) and the GCP project and App Engine module
are derived from the service's category; or pass `project`/`module` explicitly. `contains`, `filter`
(raw Cloud Logging filter), and `limit` narrow it further.

**No GCP credential lives in this repo, and cloning it grants no access to anyone's logs.** The tool
uses [Application Default Credentials](https://cloud.google.com/docs/authentication/application-default-credentials),
resolved at runtime from the environment:

| Where | Identity used |
|---|---|
| Deployed on App Engine | `trevorism-project@appspot.gserviceaccount.com`, from the GCP metadata server |
| Local development | whatever `gcloud auth application-default login` left in your profile |
| A fresh clone, elsewhere | that person's own ADC — so their own projects, never Trevorism's |

Note the one way this tool differs from every other: it acts as the *server's* Google identity rather
than forwarding the caller's Trevorism token, so downstream `@Secure` permission checks don't narrow it.
Any token that can reach this server can read the logs its service account can read.

Granting the service account access to a project is a one-time IAM step, done outside this repo:

```bash
gcloud projects add-iam-policy-binding trevorism-data \
  --member=serviceAccount:trevorism-project@appspot.gserviceaccount.com \
  --role=roles/logging.viewer
```

Without it the tool returns a `403` naming the project and the missing role. Locally, with no ADC at all,
it says so and points at `gcloud auth application-default login`.

## Build, test, deploy

```bash
gradle clean build       # compile + unit tests (JUnit5) + jacoco
gradle acceptance        # cucumber acceptance tests against the deployed instance
```

Deploys to GCP App Engine on push to `master` (`.github/workflows/deploy.yml`), which also runs the
acceptance suite (`accept.yml`) against the fresh deploy.
