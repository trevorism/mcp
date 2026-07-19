package com.trevorism.mcp

import com.trevorism.https.SecureHttpClient
import groovy.json.JsonOutput
import org.apache.hc.client5.http.HttpResponseException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Hand-rolled MCP server core (tools-only). Speaks JSON-RPC 2.0; the transport
 * (McpController) is responsible only for HTTP framing. Every downstream call goes
 * through the request-scoped pass-through client, so it carries the caller's JWT.
 */
@jakarta.inject.Singleton
class TrevorismMcpServer {

    private static final Logger log = LoggerFactory.getLogger(TrevorismMcpServer)
    private static final String PROTOCOL_VERSION = "2025-03-26"

    private final SecureHttpClient httpClient

    TrevorismMcpServer(SecureHttpClient passThruSecureHttpClient) {
        this.httpClient = passThruSecureHttpClient
    }

    /**
     * Handle a single JSON-RPC request. Returns the response Map, or {@code null}
     * for notifications (which get no reply).
     */
    Map handle(Map request) {
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
                    return result(id, [tools: toolDefinitions()])
                case "tools/call":
                    return result(id, callTool(request.params as Map))
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
                        description: "List discoverable Trevorism platform services with base URLs.",
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

    private Map callTool(Map params) {
        String name = params?.name
        Map args = (params?.arguments ?: [:]) as Map
        switch (name) {
            case "list_trevorism_services":
                // Spike: hardcoded. Phase 3 replaces this with ServiceRegistry (active endpoint).
                return toolText(JsonOutput.toJson([
                        [name: "data", baseUrl: "https://data.trevorism.com", summary: "Data access facade"],
                        [name: "testing", baseUrl: "https://testing.trevorism.com", summary: "Test suite registry"]
                ]))
            case "ping_service":
                return callApi("GET", "${args.baseUrl}/ping", null)
            case "call_trevorism_api":
                String url = "${args.baseUrl}${args.path}"
                return callApi((args.method ?: "GET").toString().toUpperCase(), url, args.body as String)
            default:
                return toolError("Unknown tool: ${name}")
        }
    }

    /** Perform the downstream call via the pass-through client (caller's JWT is attached). */
    private Map callApi(String method, String url, String body) {
        try {
            String response
            switch (method) {
                case "GET": response = httpClient.get(url); break
                case "POST": response = httpClient.post(url, body ?: ""); break
                case "PUT": response = httpClient.put(url, body ?: ""); break
                case "DELETE": response = httpClient.delete(url); break
                default: return toolError("Unsupported method: ${method}")
            }
            return toolText(response)
        } catch (HttpResponseException e) {
            // Surface downstream status (e.g. 401 on an expired/absent user token) clearly.
            return toolError("Downstream ${e.statusCode}: ${e.message}")
        } catch (Exception e) {
            return toolError("Call failed: ${e.message}")
        }
    }

    private static Map toolText(String text) {
        [content: [[type: "text", text: text ?: ""]], isError: false]
    }

    private static Map toolError(String text) {
        [content: [[type: "text", text: text ?: ""]], isError: true]
    }

    private static Map result(id, Object payload) {
        [jsonrpc: "2.0", id: id, result: payload]
    }

    private static Map error(id, int code, String message) {
        [jsonrpc: "2.0", id: id, error: [code: code, message: message]]
    }
}
