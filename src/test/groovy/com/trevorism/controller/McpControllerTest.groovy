package com.trevorism.controller

import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import org.junit.jupiter.api.Test

class McpControllerTest {

    @Test
    void testGetOnTheMcpEndpointIsMethodNotAllowed() {
        McpController controller = new McpController(null, null)
        HttpResponse<?> response = controller.stream()
        assert HttpStatus.METHOD_NOT_ALLOWED == response.status()
    }
}
