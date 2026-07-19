package com.trevorism.service

import org.junit.jupiter.api.Test

class SpecHarvesterTest {

    @Test
    void testExtractSpecPathFromSwaggerUi() {
        String html = """
            window.onload = function() {
              const ui = SwaggerUIBundle({
                url: contextPath + '/swagger/data-0.9.0.yml',
                dom_id: '#swagger-ui'
              })
            }
        """
        assert SpecHarvester.extractSpecPath(html) == "/swagger/data-0.9.0.yml"
    }

    @Test
    void testExtractSpecPathReturnsNullWhenAbsent() {
        assert SpecHarvester.extractSpecPath("<html>no spec here</html>") == null
    }

    @Test
    void testSummarizeExtractsOperationsAndSecuredFlag() {
        Map spec = [
                info : [title: "Data", version: "0.9.0"],
                paths: [
                        "/object/"       : [get: [summary: "Get all objects types **Secure"]],
                        "/ping"          : [get: [summary: "Returns 'pong' on success"]],
                        "/object/{kind}" : [post: [summary: "Create an object **Secure"]]
                ]
        ]
        Map result = SpecHarvester.summarize("https://data.trevorism.com", spec)

        assert result.title == "Data"
        assert result.version == "0.9.0"
        assert result.operations.size() == 3

        def secured = result.operations.find { it.path == "/object/" }
        assert secured.method == "GET"
        assert secured.secured == true

        def open = result.operations.find { it.path == "/ping" }
        assert open.secured == false
    }

    @Test
    void testSummarizeHandlesEmptySpec() {
        Map result = SpecHarvester.summarize("https://x.trevorism.com", [:])
        assert result.operations == []
    }
}
