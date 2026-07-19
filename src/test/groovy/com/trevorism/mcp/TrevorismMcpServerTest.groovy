package com.trevorism.mcp

import com.trevorism.https.SecureHttpClient
import org.junit.jupiter.api.Test

class TrevorismMcpServerTest {

    @Test
    void testInitializeReturnsProtocolAndServerInfo() {
        def server = new TrevorismMcpServer(null as SecureHttpClient)
        Map response = server.handle([jsonrpc: "2.0", id: 1, method: "initialize", params: [:]])

        assert response.jsonrpc == "2.0"
        assert response.id == 1
        assert response.result.protocolVersion == "2025-03-26"
        assert response.result.serverInfo.name == "trevorism-mcp"
    }

    @Test
    void testToolsListExposesMetaTools() {
        def server = new TrevorismMcpServer(null as SecureHttpClient)
        Map response = server.handle([jsonrpc: "2.0", id: 2, method: "tools/list"])

        def names = response.result.tools.collect { it.name }
        assert names.containsAll(["list_trevorism_services", "ping_service", "call_trevorism_api"])
    }

    @Test
    void testNotificationReturnsNull() {
        def server = new TrevorismMcpServer(null as SecureHttpClient)
        assert server.handle([jsonrpc: "2.0", method: "notifications/initialized"]) == null
    }

    @Test
    void testUnknownMethodReturnsMethodNotFound() {
        def server = new TrevorismMcpServer(null as SecureHttpClient)
        Map response = server.handle([jsonrpc: "2.0", id: 3, method: "does/notExist"])
        assert response.error.code == -32601
    }

    @Test
    void testCallToolForwardsThroughSecureHttpClient() {
        String requestedUrl = null
        def mockClient = [
                get: { String url -> requestedUrl = url; return "pong" }
        ] as SecureHttpClient

        def server = new TrevorismMcpServer(mockClient)
        Map response = server.handle([
                jsonrpc: "2.0", id: 4, method: "tools/call",
                params : [name: "ping_service", arguments: [baseUrl: "https://data.trevorism.com"]]
        ])

        assert requestedUrl == "https://data.trevorism.com/ping"
        assert response.result.isError == false
        assert response.result.content[0].text == "pong"
    }

    @Test
    void testCallTrevorismApiBuildsUrlAndForwards() {
        String requestedUrl = null
        def mockClient = [
                get: { String url -> requestedUrl = url; return "[]" }
        ] as SecureHttpClient

        def server = new TrevorismMcpServer(mockClient)
        server.handle([
                jsonrpc: "2.0", id: 5, method: "tools/call",
                params : [name: "call_trevorism_api",
                          arguments: [baseUrl: "https://testing.trevorism.com", method: "get", path: "/api/suite"]]
        ])

        assert requestedUrl == "https://testing.trevorism.com/api/suite"
    }
}
