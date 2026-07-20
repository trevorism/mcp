Feature: Context Root of this API
  In order to use the MCP control plane, it must be available

  Scenario: ContextRoot https
    Given the mcp application is alive
    When I navigate to https://mcp.project.trevorism.com
    Then the API returns a link to the help page

  Scenario: Ping https
    Given the mcp application is alive
    When I navigate to /ping on https://mcp.project.trevorism.com
    Then pong is returned, to indicate the service is alive
