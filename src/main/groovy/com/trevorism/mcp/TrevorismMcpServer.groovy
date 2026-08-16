package com.trevorism.mcp

import com.trevorism.AppVersion
import com.trevorism.auth.ClaimsInspector
import com.trevorism.client.PassThroughClient
import com.trevorism.logs.CloudLoggingClient
import com.trevorism.logs.LogQuery
import com.trevorism.logs.ServiceLocator
import com.trevorism.mcp.curated.CuratedToolRegistry
import com.trevorism.model.ServiceEntry
import com.trevorism.service.ServiceRegistry
import com.trevorism.service.SpecHarvester
import com.trevorism.util.HostAllowlist
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
    private static final String PROTOCOL_VERSION = "2025-06-18"
    private static final Set<String> SUPPORTED_VERSIONS = ["2025-06-18", "2025-03-26", "2024-11-05"].toSet()

    private final ServiceRegistry registry
    private final SpecHarvester specHarvester
    private final PassThroughClient passThroughClient
    private final CuratedToolRegistry curatedTools
    private final ClaimsInspector claimsInspector
    private final CloudLoggingClient loggingClient
    private final ServiceLocator serviceLocator

    TrevorismMcpServer(ServiceRegistry registry, SpecHarvester specHarvester,
                       PassThroughClient passThroughClient, CuratedToolRegistry curatedTools,
                       ClaimsInspector claimsInspector, CloudLoggingClient loggingClient,
                       ServiceLocator serviceLocator) {
        this.registry = registry
        this.specHarvester = specHarvester
        this.passThroughClient = passThroughClient
        this.curatedTools = curatedTools
        this.claimsInspector = claimsInspector
        this.loggingClient = loggingClient
        this.serviceLocator = serviceLocator
    }

    Map handle(Map request, String accessToken) {
        String method = request?.method
        if (!method) {
            return error(request?.id, -32600, "Invalid Request: missing method")
        }
        boolean isNotification = !request.containsKey("id")
        def id = request.id
        try {
            switch (method) {
                case "initialize":
                    return result(id, initialize(request.params as Map))
                case "ping":
                    return result(id, [:])
                case "tools/list":
                    return result(id, [tools: toolDefinitions() + curatedTools.toolDefinitions()])
                case "tools/call":
                    return result(id, callTool(request.params as Map, accessToken))
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

    private static Map initialize(Map params) {
        String requested = params?.protocolVersion as String
        String negotiated = (requested && SUPPORTED_VERSIONS.contains(requested)) ? requested : PROTOCOL_VERSION
        [
                protocolVersion: negotiated,
                capabilities   : [tools: [listChanged: false]],
                serverInfo     : [name: "trevorism-mcp", version: AppVersion.SEMVER]
        ]
    }

    /** A read-only tool that reaches out to external services. */
    private static Map readOnly(String title) {
        [title: title, readOnlyHint: true, openWorldHint: true]
    }

    private static List<Map> toolDefinitions() {
        [
                [
                        name       : "list_trevorism_services",
                        description: "List discoverable Trevorism platform services with resolved base URLs.",
                        inputSchema: [type: "object", properties: [:], required: []],
                        annotations: readOnly("List services")
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
                                      required  : []],
                        annotations: readOnly("Describe service")
                ],
                [
                        name       : "whoami",
                        description: "Show the caller's identity and permissions (subject, role, permissions, tenant) " +
                                "decoded from the token. Authorization is enforced downstream by these permissions.",
                        inputSchema: [type: "object", properties: [:], required: []],
                        // Decodes the token locally — no external interaction.
                        annotations: [title: "Who am I", readOnlyHint: true, openWorldHint: false]
                ],
                [
                        name       : "ping_service",
                        description: "Liveness check for a service: GET {baseUrl}/ping, expects 'pong'.",
                        inputSchema: [type: "object", properties: [baseUrl: [type: "string"]], required: ["baseUrl"]],
                        annotations: readOnly("Ping service")
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
                                      required  : ["baseUrl", "method", "path"]],
                        // Generic escape hatch: it can issue any method, so treat it as potentially destructive.
                        annotations: [title: "Call Trevorism API", readOnlyHint: false, destructiveHint: true, openWorldHint: true]
                ],
                [
                        name       : "read_gcloud_logs",
                        description: "Read Google Cloud Logging entries for a deployed Trevorism service. " +
                                "Give 'service' (a Trevorism service name, whose GCP project and App Engine " +
                                "module are resolved for you), or 'project' plus optionally 'module'. " +
                                "Newest entries first. Uses this server's own Google identity, not the caller's token.",
                        inputSchema: [type      : "object",
                                      properties: [
                                              service : [type       : "string",
                                                         description: "Trevorism service name, e.g. 'event' or 'mcp'"],
                                              project : [type       : "string",
                                                         description: "GCP project id, e.g. 'trevorism-data'. " +
                                                                 "Overrides the one derived from 'service'."],
                                              module  : [type       : "string",
                                                         description: "App Engine service (module) id; 'default' for a " +
                                                                 "category's default service. Omit for all modules."],
                                              severity: [type       : "string", 'enum': LogQuery.SEVERITIES,
                                                         description: "Minimum severity, e.g. ERROR"],
                                              since   : [type       : "string",
                                                         description: "How far back to look: 30m, 2h, 3d (default 1h, max 30d)"],
                                              contains: [type       : "string", description: "Only entries containing this text"],
                                              filter  : [type       : "string",
                                                         description: "Extra raw Cloud Logging filter, AND-ed with the rest"],
                                              limit   : [type       : "integer", description: "Max entries (default 50, max 500)"]
                                      ],
                                      required  : []],
                        annotations: readOnly("Read Google Cloud logs")
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
            case "read_gcloud_logs":
                return readLogs(args, bearer)
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
        if (!HostAllowlist.isAllowed(baseUrl)) {
            return PassThroughClient.toolError("Refused: '${baseUrl}' is not a Trevorism (*.trevorism.com) host")
        }
        return PassThroughClient.toolText(JsonOutput.toJson(specHarvester.describe(baseUrl)))
    }

    private Map readLogs(Map args, String bearer) {
        String project = args.project as String
        String module = args.module as String

        if (args.service && (!project || !module)) {
            Map located = serviceLocator.locate(args.service as String, bearer)
            if (!located && !project) {
                return PassThroughClient.toolError("Could not resolve service '${args.service}' to a GCP project. " +
                        "Use list_trevorism_services to check the name, or pass 'project' explicitly.")
            }
            project = project ?: located?.project
            module = module ?: located?.module
        }
        if (!project) {
            return PassThroughClient.toolError("read_gcloud_logs requires 'service' or 'project'")
        }

        LogQuery query = new LogQuery(project: project, module: module, severity: args.severity as String,
                contains: args.contains as String, rawFilter: args.filter as String)
        if (args.since) {
            query.since = args.since as String
        }
        if (args.limit != null) {
            query.limit = args.limit as int
        }

        try {
            return PassThroughClient.toolText(JsonOutput.toJson(loggingClient.read(query)))
        } catch (IllegalArgumentException e) {
            return PassThroughClient.toolError(e.message)
        } catch (IllegalStateException e) {
            return PassThroughClient.toolError(e.message)
        } catch (Exception e) {
            log.error("Log read failed for project ${project}", e)
            return PassThroughClient.toolError("Could not read logs: ${e.message}")
        }
    }

    private static Map result(id, Object payload) {
        [jsonrpc: "2.0", id: id, result: payload]
    }

    private static Map error(id, int code, String message) {
        [jsonrpc: "2.0", id: id, error: [code: code, message: message]]
    }
}
