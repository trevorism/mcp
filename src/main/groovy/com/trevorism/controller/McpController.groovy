package com.trevorism.controller

import com.trevorism.auth.TokenManager
import com.trevorism.mcp.TrevorismMcpServer
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux

/**
 * MCP over Streamable HTTP, hand-rolled on native Micronaut/Netty.
 * POST carries JSON-RPC messages; GET opens the server->client SSE stream
 * (unused by a tools-only server, but kept open for spec-compliant clients).
 *
 * Auth: the caller presents a Trevorism user REFRESH token as the bearer; TokenManager redeems it
 * for a fresh (cached) access token, which is threaded into the tool handlers for per-user downstream
 * calls. A plain access token also works (redeem falls back to using it directly). No bearer -> 401.
 */
@Controller("/mcp")
class McpController {

    private final TrevorismMcpServer server
    private final TokenManager tokenManager

    McpController(TrevorismMcpServer server, TokenManager tokenManager) {
        this.server = server
        this.tokenManager = tokenManager
    }

    @Tag(name = "MCP")
    @Operation(summary = "MCP JSON-RPC endpoint (initialize, tools/list, tools/call)")
    @Post(consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    HttpResponse<?> rpc(@Body Map request, @Header(HttpHeaders.AUTHORIZATION) @Nullable String authorization) {
        String accessToken = tokenManager.resolveAccessToken(authorization)
        if (!accessToken) {
            return HttpResponse.unauthorized().body([
                    jsonrpc: "2.0", id: request?.id,
                    error  : [code: -32001, message: "Missing or invalid Authorization bearer token"]])
        }
        Map response = server.handle(request, accessToken)
        // Notifications get no body, per JSON-RPC.
        return response == null ? HttpResponse.accepted() : HttpResponse.ok(response)
    }

    @Tag(name = "MCP")
    @Operation(summary = "MCP server->client SSE stream")
    @Get(produces = MediaType.TEXT_EVENT_STREAM)
    Publisher<String> stream() {
        // Tools-only server: no server-initiated messages. Keep the stream open.
        return Flux.never()
    }
}
