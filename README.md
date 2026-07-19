# mcp
![Build](https://github.com/trevorism/mcp/actions/workflows/deploy.yml/badge.svg)
![GitHub last commit](https://img.shields.io/github/last-commit/trevorism/mcp)
![GitHub language count](https://img.shields.io/github/languages/count/trevorism/mcp)
![GitHub top language](https://img.shields.io/github/languages/top/trevorism/mcp)

Trevorism MCP server

How to add to claude (for example)
```powershell
$token = Get-TrevorismUserToken
claude mcp add --transport http trevorism https://mcp.project.trevorism.com/mcp --header "Authorization: Bearer $token"
```

# How to build
`gradle clean build`
