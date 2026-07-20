package com.trevorism.mcp.curated

import com.trevorism.client.PassThroughClient
import groovy.json.JsonOutput
import jakarta.inject.Singleton

/**
 * Registry of curated first-class MCP tools. Each tool maps a named, typed schema to a single downstream
 * HTTP call routed through {@link PassThroughClient} (per-user token). Data-driven: extend by adding a
 * {@link CuratedTool} to {@link #TOOLS}.
 */
@Singleton
class CuratedToolRegistry {

    private static final String DATA = "https://data.trevorism.com"
    private static final String TESTING = "https://testing.trevorism.com"

    private static final List<String> DATASOURCES = ["datastore", "bigquery", "memory"]
    private static final List<String> SUITE_KINDS = ["unit", "javascript", "cucumber", "web", "powershell", "cypress"]

    private final PassThroughClient passThroughClient
    private final List<CuratedTool> tools = buildTools()
    private final Map<String, CuratedTool> byName = tools.collectEntries { [(it.name): it] }

    CuratedToolRegistry(PassThroughClient passThroughClient) {
        this.passThroughClient = passThroughClient
    }

    List<Map> toolDefinitions() {
        tools.collect { it.toDefinition() }
    }

    boolean handles(String name) {
        byName.containsKey(name)
    }

    Map call(String name, Map args, String accessToken) {
        CuratedTool tool = byName[name]
        if (!tool) {
            return PassThroughClient.toolError("Unknown curated tool: ${name}")
        }
        args = args ?: [:]

        String path = tool.pathTemplate
        for (String p : tool.pathParams) {
            def value = args[p]
            if (value == null || value.toString().isEmpty()) {
                return PassThroughClient.toolError("Missing required argument: ${p}")
            }
            path = path.replace("{${p}}", encode(value.toString()))
        }

        String url = tool.baseUrl + path
        List<String> query = []
        for (String q : (tool.queryParams ?: [])) {
            if (args[q] != null) {
                query << "${encode(q)}=${encode(args[q].toString())}"
            }
        }
        if (query) {
            url += "?" + query.join("&")
        }

        String body = null
        if (tool.bodyObjectArg) {
            def b = args[tool.bodyObjectArg]
            if (b == null) {
                return PassThroughClient.toolError("Missing required argument: ${tool.bodyObjectArg}")
            }
            body = JsonOutput.toJson(b)
        } else if (tool.bodyFromArgs) {
            Map m = [:]
            for (String k : tool.bodyFromArgs) {
                if (args[k] != null) {
                    m[k] = args[k]
                }
            }
            body = JsonOutput.toJson(m)
        }

        return passThroughClient.callApi(tool.method, url, body, accessToken)
    }

    private static String encode(String s) {
        URLEncoder.encode(s, "UTF-8")
    }

    // ---- definitions ---------------------------------------------------------

    private static Map objectSchema(String desc) {
        [type: "object", description: desc]
    }

    private static Map stringProp(String desc, List<String> enumValues = null) {
        Map p = [type: "string", description: desc]
        if (enumValues) p['enum'] = enumValues
        return p
    }

    /** Read-only tool that reaches out to a downstream service. */
    private static Map readOnly(String title) {
        [title: title, readOnlyHint: true, openWorldHint: true]
    }

    /** Mutating tool: {@code destructive} = may overwrite/remove data; {@code idempotent} = repeat is a no-op. */
    private static Map writes(String title, boolean destructive, boolean idempotent) {
        [title: title, readOnlyHint: false, destructiveHint: destructive, idempotentHint: idempotent, openWorldHint: true]
    }

    private static List<CuratedTool> buildTools() {
        Map datasource = stringProp("Optional datasource (default datastore)", DATASOURCES)
        [
                // ---- Data (data.trevorism.com) ----
                new CuratedTool(
                        name: "list_object_types", baseUrl: DATA, method: "GET", pathTemplate: "/object",
                        description: "List all object types (kinds) available in the data service.",
                        queryParams: ["datasource"], annotations: readOnly("List object types"),
                        inputSchema: [type: "object", properties: [datasource: datasource], required: []]),
                new CuratedTool(
                        name: "get_objects", baseUrl: DATA, method: "GET", pathTemplate: "/object/{kind}",
                        description: "Get all objects of a given type/kind.",
                        pathParams: ["kind"], queryParams: ["datasource"], annotations: readOnly("Get objects"),
                        inputSchema: [type: "object", properties: [kind: stringProp("Object type/kind"), datasource: datasource], required: ["kind"]]),
                new CuratedTool(
                        name: "get_object", baseUrl: DATA, method: "GET", pathTemplate: "/object/{kind}/{id}",
                        description: "Get a single object of type {kind} by its id.",
                        pathParams: ["kind", "id"], queryParams: ["datasource"], annotations: readOnly("Get object"),
                        inputSchema: [type: "object", properties: [kind: stringProp("Object type/kind"), id: stringProp("Object id"), datasource: datasource], required: ["kind", "id"]]),
                new CuratedTool(
                        name: "create_object", baseUrl: DATA, method: "POST", pathTemplate: "/object/{kind}",
                        description: "Create an object of type {kind}.",
                        pathParams: ["kind"], queryParams: ["datasource"], bodyObjectArg: "data",
                        annotations: writes("Create object", false, false),
                        inputSchema: [type: "object", properties: [kind: stringProp("Object type/kind"), data: objectSchema("The object to create"), datasource: datasource], required: ["kind", "data"]]),
                new CuratedTool(
                        name: "update_object", baseUrl: DATA, method: "PUT", pathTemplate: "/object/{kind}/{id}",
                        description: "Update the object of type {kind} with id {id}.",
                        pathParams: ["kind", "id"], queryParams: ["datasource"], bodyObjectArg: "data",
                        annotations: writes("Update object", true, true),
                        inputSchema: [type: "object", properties: [kind: stringProp("Object type/kind"), id: stringProp("Object id"), data: objectSchema("The updated object"), datasource: datasource], required: ["kind", "id", "data"]]),
                new CuratedTool(
                        name: "delete_object", baseUrl: DATA, method: "DELETE", pathTemplate: "/object/{kind}/{id}",
                        description: "Delete the object of type {kind} with id {id}.",
                        pathParams: ["kind", "id"], queryParams: ["datasource"],
                        annotations: writes("Delete object", true, true),
                        inputSchema: [type: "object", properties: [kind: stringProp("Object type/kind"), id: stringProp("Object id"), datasource: datasource], required: ["kind", "id"]]),
                new CuratedTool(
                        name: "query_data", baseUrl: DATA, method: "POST", pathTemplate: "/query",
                        description: "Run a data query and get results. Pass the query specification object.",
                        queryParams: ["datasource"], bodyObjectArg: "query", annotations: readOnly("Query data"),
                        inputSchema: [type: "object", properties: [query: objectSchema("The query specification"), datasource: datasource], required: ["query"]]),

                // ---- Testing (testing.trevorism.com) ----
                new CuratedTool(
                        name: "list_test_suites", baseUrl: TESTING, method: "GET", pathTemplate: "/api/suite",
                        description: "List all registered test suites and their last-run status.",
                        annotations: readOnly("List test suites"),
                        inputSchema: [type: "object", properties: [:], required: []]),
                new CuratedTool(
                        name: "get_test_suite", baseUrl: TESTING, method: "GET", pathTemplate: "/api/suite/{id}",
                        description: "Get a registered test suite by its id.",
                        pathParams: ["id"], annotations: readOnly("Get test suite"),
                        inputSchema: [type: "object", properties: [id: stringProp("Test suite id")], required: ["id"]]),
                new CuratedTool(
                        name: "run_test_suite", baseUrl: TESTING, method: "POST", pathTemplate: "/api/suite/{id}",
                        description: "Invoke (run) the registered test suite with the given id.",
                        pathParams: ["id"], annotations: writes("Run test suite", false, false),
                        inputSchema: [type: "object", properties: [id: stringProp("Test suite id")], required: ["id"]]),
                new CuratedTool(
                        name: "register_test_suite", baseUrl: TESTING, method: "POST", pathTemplate: "/api/suite",
                        description: "Register a new test suite. 'source' must equal the repo name.",
                        bodyFromArgs: ["name", "source", "kind"], annotations: writes("Register test suite", false, false),
                        inputSchema: [type: "object", properties: [
                                name  : stringProp("Suite name"),
                                source: stringProp("Repo name (used for dispatch + event matching)"),
                                kind  : stringProp("Test kind", SUITE_KINDS)], required: ["name", "source", "kind"]]),
        ]
    }
}
