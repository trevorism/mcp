# mcp
![Build](https://github.com/trevorism/mcp/actions/workflows/deploy.yml/badge.svg)
![GitHub last commit](https://img.shields.io/github/last-commit/trevorism/mcp)
![GitHub language count](https://img.shields.io/github/languages/count/trevorism/mcp)
![GitHub top language](https://img.shields.io/github/languages/top/trevorism/mcp)

The Trevorism MCP server 

## Client setup

Mint a user refresh token, then register the server. The longer the refresh token TTL, the
longer the client session lasts without re-minting.

```powershell
$token = Get-TrevorismRefreshToken 
claude mcp add --transport http trevorism https://mcp.project.trevorism.com/mcp --header "Authorization: Bearer $token"
```
## Build, test, deploy

```bash
gradle clean build  
```
