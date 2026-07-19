package com.trevorism.mcp

import com.trevorism.auth.ClaimsInspector
import com.trevorism.client.PassThroughClient
import com.trevorism.mcp.curated.CuratedToolRegistry
import com.trevorism.model.ServiceEntry
import com.trevorism.service.ServiceRegistry
import com.trevorism.service.SpecHarvester
import groovy.json.JsonOutput
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Hand-rolled MCP server core (tools-only). Speaks JSON-RPC 2.0; the transport
 * (McpController) is responsible only for HTTP framing.
 *
 * The caller's bearer token is threaded in from the controller: discovery tools use it for the
 * @Secure category lookups, while ping/call go through the request-scoped pass-through client.
 */
@Singleton
class TrevorismMcpServer {

    private static final Logger log = LoggerFactory.getLogger(TrevorismMcpServer)
    private static final String PROTOCOL_VERSION = "2025-03-26"

    private final ServiceRegistry registry
    private final SpecHarvester specHarvester
    private final PassThroughClient passThroughClient
    private final CuratedToolRegistry curatedTools
    private final ClaimsInspector claimsInspector

    TrevorismMcpServer(ServiceRegistry registry, SpecHarvester specHarvester,
                       PassThroughClient passThroughClient, CuratedToolRegistry curatedTools,
                       ClaimsInspector claimsInspector) {
        this.registry = registry
        this.specHarvester = specHarvester
        this.passThroughClient = passThroughClient
        this.curatedTools = curatedTools
        this.claimsInspector = claimsInspector
    }

    /**
     * Handle a single JSON-RPC request. {@code bearer} is the caller's raw JWT (no "Bearer " prefix
     * required — accepted either way). Returns the response Map, or {@code null} for notifications.
     */
    Map handle(Map request, String bearer) {
        String method = request?.method
        if (!method) {
            return error(request?.id, -32600, "Invalid Request: missing method")
        }
        boolean isNotification = !request.containsKey("id")
        def id = request.id
        try {
            switch (method) {
                case "initialize":
                    return result(id, initialize())
                case "ping":
                    return result(id, [:])
                case "tools/list":
                    return result(id, [tools: toolDefinitions() + curatedTools.toolDefinitions()])
                case "tools/call":
                    return result(id, callTool(request.params as Map, stripBearer(bearer)))
                default:
                    if (method.startsWith("notifications/")) {
                        return null
                    }
                    return isNotification ? null : error(id, -32601, "Method not found: ${method}")
            }
        } catch (Exception e) {
            log.error("MCP handler error for method ${method}", e)
            return isNotification ? null : error(id, -32603, e.message)
        }
    }

    private static Map initialize() {
        [
                protocolVersion: PROTOCOL_VERSION,
                capabilities   : [tools: [listChanged: false]],
                serverInfo     : [name: "trevorism-mcp", version: "0-1-0"]
        ]
    }

    private static List<Map> toolDefinitions() {
        [
                [
                        name       : "list_trevorism_services",
                        description: "List discoverable Trevorism platform services with resolved base URLs.",
                        inputSchema: [type: "object", properties: [:], required: []]
                ],
                [
                        name       : "describe_service",
                        description: "Summarize a service's API operations (method, path, whether it needs auth) " +
                                "from its OpenAPI spec. Give a service name, or a baseUrl directly.",
                        inputSchema: [type      : "object",
                                      properties: [
                                              name   : [type: "string", description: "Service name, e.g. 'data'"],
                                              baseUrl: [type: "string", description: "Base URL (alternative to name)"]
                                      ],
                                      required  : []]
                ],
                [
                        name       : "whoami",
                        description: "Show the caller's identity and permissions (subject, role, permissions, tenant) " +
                                "decoded from the token. Authorization is enforced downstream by these permissions.",
                        inputSchema: [type: "object", properties: [:], required: []]
                ],
                [
                        name       : "ping_service",
                        description: "Liveness check for a service: GET {baseUrl}/ping, expects 'pong'.",
                        inputSchema: [type: "object", properties: [baseUrl: [type: "string"]], required: ["baseUrl"]]
                ],
                [
                        name       : "call_trevorism_api",
                        description: "Call a Trevorism REST endpoint, forwarding the caller's JWT.",
                        inputSchema: [type      : "object",
                                      properties: [
                                              baseUrl: [type: "string"],
                                              method : [type: "string", description: "GET|POST|PUT|DELETE"],
                                              path   : [type: "string"],
                                              body   : [type: "string", description: "JSON body for POST/PUT"]
                                      ],
                                      required  : ["baseUrl", "method", "path"]]
                ]
        ]
    }

    private Map callTool(Map params, String bearer) {
        String name = params?.name
        Map args = (params?.arguments ?: [:]) as Map
        if (curatedTools.handles(name)) {
            return curatedTools.call(name, args, bearer)
        }
        switch (name) {
            case "list_trevorism_services":
                List services = registry.listServices(bearer).collect { (it as ServiceEntry).toMap() }
                return PassThroughClient.toolText(JsonOutput.toJson(services))
            case "whoami":
                try {
                    return PassThroughClient.toolText(JsonOutput.toJson(claimsInspector.inspect(bearer)))
                } catch (Exception e) {
                    return PassThroughClient.toolError("Could not decode token: ${e.message}")
                }
            case "describe_service":
                return describeService(args, bearer)
            case "ping_service":
                return passThroughClient.callApi("GET", "${args.baseUrl}/ping", null, bearer)
            case "call_trevorism_api":
                String url = "${args.baseUrl}${args.path}"
                return passThroughClient.callApi((args.method ?: "GET") as String, url, args.body as String, bearer)
            default:
                return PassThroughClient.toolError("Unknown tool: ${name}")
        }
    }

    private Map describeService(Map args, String bearer) {
        String baseUrl = args.baseUrl as String
        if (!baseUrl && args.name) {
            ServiceEntry entry = registry.byName(args.name as String, bearer)
            if (!entry) {
                return PassThroughClient.toolError("Unknown service: ${args.name}")
            }
            baseUrl = entry.baseUrl
        }
        if (!baseUrl) {
            return PassThroughClient.toolError("describe_service requires 'name' or 'baseUrl'")
        }
        return PassThroughClient.toolText(JsonOutput.toJson(specHarvester.describe(baseUrl)))
    }

    private static String stripBearer(String bearer) {
        if (!bearer) return bearer
        return bearer.toLowerCase().startsWith("bearer ") ? bearer.substring(7).trim() : bearer.trim()
    }

    private static Map result(id, Object payload) {
        [jsonrpc: "2.0", id: id, result: payload]
    }

    private static Map error(id, int code, String message) {
        [jsonrpc: "2.0", id: id, error: [code: code, message: message]]
    }
}
