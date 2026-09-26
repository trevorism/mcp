package com.trevorism.controller

import com.trevorism.auth.TokenManager
import com.trevorism.mcp.TrevorismMcpServer
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import org.junit.jupiter.api.Test

class McpControllerTest {

    private static TokenManager tokenManagerAccepting(String validHeader, String accessToken) {
        new TokenManager() {
            @Override
            String authenticate(String authorizationHeader) {
                authorizationHeader == validHeader ? accessToken : null
            }
        }
    }

    private static TrevorismMcpServer echoServer(List<String> seenTokens) {
        new TrevorismMcpServer(null, null, null, null, null, null, null) {
            @Override
            Map handle(Map request, String accessToken) {
                seenTokens << accessToken
                return [jsonrpc: "2.0", id: request.id, result: [:]]
            }
        }
    }

    @Test
    void testGetOnTheMcpEndpointIsMethodNotAllowed() {
        McpController controller = new McpController(null, null)
        HttpResponse<?> response = controller.stream()
        assert HttpStatus.METHOD_NOT_ALLOWED == response.status()
    }

    @Test
    void testInvalidBearerIsUnauthorizedAndNeverReachesTheServer() {
        List<String> seen = []
        McpController controller = new McpController(echoServer(seen), tokenManagerAccepting("Bearer good", "access"))

        HttpResponse<?> response = controller.rpc([jsonrpc: "2.0", id: 1, method: "tools/list"], "Bearer not-a-token")

        assert HttpStatus.UNAUTHORIZED == response.status()
        assert seen.isEmpty()
    }

    @Test
    void testValidBearerForwardsTheResolvedAccessToken() {
        List<String> seen = []
        McpController controller = new McpController(echoServer(seen), tokenManagerAccepting("Bearer good", "access"))

        HttpResponse<?> response = controller.rpc([jsonrpc: "2.0", id: 1, method: "tools/list"], "Bearer good")

        assert HttpStatus.OK == response.status()
        assert seen == ["access"]
    }
}
