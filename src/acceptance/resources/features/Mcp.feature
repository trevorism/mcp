Feature: MCP endpoint
  In order for agents to use the control plane, the MCP endpoint must speak JSON-RPC and be secured

  Scenario: Initialize handshake
    When an authenticated initialize request is sent to the mcp endpoint
    Then the response advertises the mcp server and protocol version

  Scenario: Tools are listed
    When an authenticated tools list request is sent to the mcp endpoint
    Then the meta tools and curated tools are present

  Scenario: Unauthenticated requests are rejected
    When an unauthenticated request is sent to the mcp endpoint
    Then the request is rejected
