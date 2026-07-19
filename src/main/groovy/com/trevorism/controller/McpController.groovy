package com.trevorism.controller

import com.trevorism.mcp.TrevorismMcpServer
import com.trevorism.secure.Roles
import com.trevorism.secure.Secure
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
 * The tool handler runs in this inbound request's scope, so the request-scoped
 * pass-through client can read the caller's Authorization header.
 */
@Controller("/mcp")
class McpController {

    private final TrevorismMcpServer server

    McpController(TrevorismMcpServer server) {
        this.server = server
    }

    @Tag(name = "MCP")
    @Operation(summary = "MCP JSON-RPC endpoint (initialize, tools/list, tools/call) **Secure")
    @Post(consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    @Secure(value = Roles.USER, allowInternal = true)
    HttpResponse<?> rpc(@Body Map request, @Header(HttpHeaders.AUTHORIZATION) String authorization) {
        Map response = server.handle(request, authorization)
        // Notifications get no body, per JSON-RPC.
        return response == null ? HttpResponse.accepted() : HttpResponse.ok(response)
    }

    @Tag(name = "MCP")
    @Operation(summary = "MCP server->client SSE stream **Secure")
    @Get(produces = MediaType.TEXT_EVENT_STREAM)
    @Secure(value = Roles.USER, allowInternal = true)
    Publisher<String> stream() {
        // Tools-only server: no server-initiated messages. Keep the stream open.
        return Flux.never()
    }
}
