package com.trevorism.mcp.curated

import com.trevorism.client.PassThroughClient
import org.junit.jupiter.api.Test

class CuratedToolRegistryTest {

    /** Records the downstream call instead of performing it. */
    static class Recorder extends PassThroughClient {
        List<Map> calls = []

        @Override
        Map callApi(String method, String url, String body, String accessToken) {
            calls << [method: method, url: url, body: body, accessToken: accessToken]
            return PassThroughClient.toolText("ok")
        }
    }

    private static CuratedToolRegistry reg(Recorder rec) {
        new CuratedToolRegistry(rec)
    }

    @Test
    void testToolDefinitionsIncludeAllCuratedTools() {
        def names = reg(new Recorder()).toolDefinitions().collect { it.name }
        assert names.containsAll([
                "list_object_types", "list_objects", "get_object", "create_object",
                "list_lists", "get_list", "get_list_content",
                "list_test_suites", "get_test_suite", "run_test_suite", "register_test_suite"])
        assert names.size() == 11
    }

    @Test
    void testMutatingToolsCarryDestructiveAnnotations() {
        def defs = reg(new Recorder()).toolDefinitions().collectEntries { [(it.name): it] }
        assert defs.get_object.annotations.readOnlyHint == true
        assert defs.create_object.annotations.destructiveHint == false
    }

    @Test
    void testHandles() {
        def r = reg(new Recorder())
        assert r.handles("run_test_suite")
        assert !r.handles("call_trevorism_api")   // meta-tool, not curated
    }

    @Test
    void testPathTemplating() {
        def rec = new Recorder()
        reg(rec).call("get_object", [kind: "app", id: "123"], "tok")
        assert rec.calls[0].method == "GET"
        assert rec.calls[0].url == "https://data.trevorism.com/object/app/123"
        assert rec.calls[0].accessToken == "tok"
    }

    @Test
    void testSpacesArePercentEncodedAndPlusSignsPreserved() {
        def rec = new Recorder()
        reg(rec).call("get_object", [kind: "my app", id: "a+b", datasource: "big query"], "tok")
        assert rec.calls[0].url == "https://data.trevorism.com/object/my%20app/a%2Bb?datasource=big%20query"
    }

    @Test
    void testBodyFromObjectArg() {
        def rec = new Recorder()
        reg(rec).call("create_object", [kind: "app", data: [name: "x", n: 1]], "tok")
        assert rec.calls[0].method == "POST"
        assert rec.calls[0].url == "https://data.trevorism.com/object/app"
        assert rec.calls[0].body == '{"name":"x","n":1}'
    }

    @Test
    void testListToolsRouteToListService() {
        def rec = new Recorder()
        def registry = reg(rec)
        registry.call("list_lists", [:], "tok")
        registry.call("get_list", [id: "123"], "tok")
        registry.call("get_list_content", [id: "123"], "tok")
        assert rec.calls[0].url == "https://list.data.trevorism.com/object"
        assert rec.calls[1].url == "https://list.data.trevorism.com/object/123"
        assert rec.calls[2].url == "https://list.data.trevorism.com/object/123/content"
        assert rec.calls.every { it.method == "GET" }
    }

    @Test
    void testBodyFromArgs() {
        def rec = new Recorder()
        reg(rec).call("register_test_suite", [name: "unit_foo", source: "foo", kind: "unit"], "tok")
        assert rec.calls[0].method == "POST"
        assert rec.calls[0].url == "https://testing.trevorism.com/api/suite"
        assert rec.calls[0].body.contains('"name":"unit_foo"')
        assert rec.calls[0].body.contains('"source":"foo"')
        assert rec.calls[0].body.contains('"kind":"unit"')
    }

    @Test
    void testRunTestSuiteNoBody() {
        def rec = new Recorder()
        reg(rec).call("run_test_suite", [id: "999"], "tok")
        assert rec.calls[0].method == "POST"
        assert rec.calls[0].url == "https://testing.trevorism.com/api/suite/999"
        assert rec.calls[0].body == null
    }

    @Test
    void testMissingRequiredPathParamIsError() {
        def rec = new Recorder()
        Map result = reg(rec).call("get_object", [kind: "app"], "tok")   // missing id
        assert result.isError == true
        assert rec.calls.isEmpty()   // no downstream call attempted
    }
}
