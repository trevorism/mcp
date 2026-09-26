package com.trevorism.controller

import com.trevorism.auth.TokenManager
import com.trevorism.mcp.TrevorismMcpServer
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

/**
 * MCP over Streamable HTTP, hand-rolled on native Micronaut/Netty.
 * POST carries JSON-RPC messages; GET would open the server->client SSE stream, which a
 * tools-only server does not offer, so it answers 405 as the transport spec prescribes.
 *
 * Auth: the caller presents a Trevorism user REFRESH token as the bearer; TokenManager redeems it
 * for a fresh (cached) access token, which is threaded into the tool handlers for per-user downstream
 * calls. A plain access token also works (redeem falls back to using it directly). The resolved token
 * must verify against the signing key; otherwise -> 401.
 */
@Controller("/mcp")
@ExecuteOn(TaskExecutors.BLOCKING)
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
        String accessToken = tokenManager.authenticate(authorization)
        if (!accessToken) {
            return HttpResponse.unauthorized().body([
                    jsonrpc: "2.0", id: request?.id,
                    error  : [code: -32001, message: "Missing or invalid Authorization bearer token"]])
        }
        Map response = server.handle(request, accessToken)
        return response == null ? HttpResponse.accepted() : HttpResponse.ok(response)
    }

    @Tag(name = "MCP")
    @Operation(summary = "No server->client SSE stream is offered; this is a tools-only server (spec: 405)")
    @Get(produces = MediaType.TEXT_EVENT_STREAM)
    HttpResponse<?> stream() {
        return HttpResponse.status(HttpStatus.METHOD_NOT_ALLOWED)
    }
}
