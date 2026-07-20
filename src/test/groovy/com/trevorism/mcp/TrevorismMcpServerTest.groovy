package com.trevorism.mcp

import com.trevorism.auth.ClaimsInspector
import com.trevorism.client.PassThroughClient
import com.trevorism.mcp.curated.CuratedToolRegistry
import com.trevorism.model.ServiceEntry
import com.trevorism.service.ServiceRegistry
import com.trevorism.service.SpecHarvester
import org.junit.jupiter.api.Test

class TrevorismMcpServerTest {

    private static ServiceRegistry stubRegistry(List<ServiceEntry> entries) {
        new ServiceRegistry() {
            @Override
            List<ServiceEntry> listServices(String bearer) { entries }
            @Override
            ServiceEntry byName(String name, String bearer) { entries.find { it.name == name } }
        }
    }

    private static SpecHarvester stubHarvester(Map result) {
        new SpecHarvester() {
            @Override
            Map describe(String baseUrl) { result }
        }
    }

    private static PassThroughClient recordingPassThrough(List calls) {
        new PassThroughClient() {
            @Override
            Map callApi(String method, String url, String body, String accessToken) {
                calls << [method: method, url: url, body: body, accessToken: accessToken]
                return PassThroughClient.toolText("ok")
            }
        }
    }

    private static ClaimsInspector stubInspector() {
        new ClaimsInspector() {
            @Override
            Map inspect(String accessToken) {
                [subject: "me", role: "user", permissions: "CRE", tenant: "t1", accessToken: accessToken]
            }
        }
    }

    private static TrevorismMcpServer server(ServiceRegistry r, SpecHarvester h, PassThroughClient p) {
        new TrevorismMcpServer(r, h, p, new CuratedToolRegistry(p), stubInspector())
    }

    @Test
    void testInitialize() {
        def s = server(stubRegistry([]), stubHarvester([:]), recordingPassThrough([]))
        Map response = s.handle([jsonrpc: "2.0", id: 1, method: "initialize", params: [:]], "Bearer t")
        assert response.result.protocolVersion == "2025-03-26"
        assert response.result.serverInfo.name == "trevorism-mcp"
    }

    @Test
    void testToolsListHasMetaAndCuratedTools() {
        def s = server(stubRegistry([]), stubHarvester([:]), recordingPassThrough([]))
        def names = s.handle([jsonrpc: "2.0", id: 2, method: "tools/list"], "Bearer t").result.tools.collect { it.name }
        // 5 meta tools (incl. whoami)
        assert names.containsAll(["list_trevorism_services", "describe_service", "whoami", "ping_service", "call_trevorism_api"])
        // curated tools appended
        assert names.containsAll(["get_object", "create_object", "run_test_suite", "register_test_suite"])
        assert names.size() == 16
    }

    @Test
    void testWhoamiDecodesToken() {
        def s = server(stubRegistry([]), stubHarvester([:]), recordingPassThrough([]))
        Map response = s.handle([
                jsonrpc: "2.0", id: 30, method: "tools/call",
                params : [name: "whoami", arguments: [:]]], "abc")
        def claims = new groovy.json.JsonSlurper().parseText(response.result.content[0].text)
        assert claims.permissions == "CRE"
        assert claims.role == "user"
        assert claims.accessToken == "abc"   // resolved access token threaded to inspector
    }

    @Test
    void testCuratedToolRoutesThroughPassThrough() {
        List calls = []
        def s = server(stubRegistry([]), stubHarvester([:]), recordingPassThrough(calls))
        s.handle([
                jsonrpc: "2.0", id: 20, method: "tools/call",
                params : [name: "get_object", arguments: [kind: "app", id: "42"]]], "tok")
        assert calls[0].method == "GET"
        assert calls[0].url == "https://data.trevorism.com/object/app/42"
        assert calls[0].accessToken == "tok"
    }

    @Test
    void testListServicesUsesRegistry() {
        def registry = stubRegistry([new ServiceEntry("data", "https://data.trevorism.com", "data")])
        def s = server(registry, stubHarvester([:]), recordingPassThrough([]))
        Map response = s.handle([
                jsonrpc: "2.0", id: 3, method: "tools/call",
                params : [name: "list_trevorism_services", arguments: [:]]], "Bearer tok")
        assert response.result.isError == false
        assert response.result.content[0].text.contains("https://data.trevorism.com")
    }

    @Test
    void testDescribeServiceResolvesByName() {
        def registry = stubRegistry([new ServiceEntry("data", "https://data.trevorism.com", "data")])
        def harvester = stubHarvester([baseUrl: "https://data.trevorism.com", title: "Data", operations: []])
        def s = server(registry, harvester, recordingPassThrough([]))
        Map response = s.handle([
                jsonrpc: "2.0", id: 4, method: "tools/call",
                params : [name: "describe_service", arguments: [name: "data"]]], "Bearer tok")
        assert response.result.isError == false
        assert response.result.content[0].text.contains("\"title\":\"Data\"")
    }

    @Test
    void testDescribeUnknownServiceIsError() {
        def s = server(stubRegistry([]), stubHarvester([:]), recordingPassThrough([]))
        Map response = s.handle([
                jsonrpc: "2.0", id: 5, method: "tools/call",
                params : [name: "describe_service", arguments: [name: "nope"]]], "Bearer tok")
        assert response.result.isError == true
    }

    @Test
    void testCallTrevorismApiDelegatesToPassThrough() {
        List calls = []
        def s = server(stubRegistry([]), stubHarvester([:]), recordingPassThrough(calls))
        // handle() receives the already-resolved access token from the controller (no "Bearer " prefix).
        s.handle([
                jsonrpc: "2.0", id: 6, method: "tools/call",
                params : [name: "call_trevorism_api",
                          arguments: [baseUrl: "https://data.trevorism.com", method: "GET", path: "/object/"]]], "tok")
        assert calls[0].method == "GET"
        assert calls[0].url == "https://data.trevorism.com/object/"
        assert calls[0].accessToken == "tok"
    }

    @Test
    void testDescribeServiceRejectsNonTrevorismHost() {
        def s = server(stubRegistry([]), stubHarvester([title: "X"]), recordingPassThrough([]))
        Map response = s.handle([
                jsonrpc: "2.0", id: 7, method: "tools/call",
                params : [name: "describe_service", arguments: [baseUrl: "https://evil.com"]]], "tok")
        assert response.result.isError == true
        assert response.result.content[0].text.contains("Refused")
    }

    @Test
    void testNotificationReturnsNull() {
        def s = server(stubRegistry([]), stubHarvester([:]), recordingPassThrough([]))
        assert s.handle([jsonrpc: "2.0", method: "notifications/initialized"], "Bearer t") == null
    }

    @Test
    void testUnknownMethodReturnsMethodNotFound() {
        def s = server(stubRegistry([]), stubHarvester([:]), recordingPassThrough([]))
        assert s.handle([jsonrpc: "2.0", id: 9, method: "does/notExist"], "Bearer t").error.code == -32601
    }
}
