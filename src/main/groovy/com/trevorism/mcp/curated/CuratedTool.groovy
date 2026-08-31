package com.trevorism.mcp.curated

import groovy.transform.CompileStatic

@CompileStatic
class CuratedTool {
    String name
    String description
    Map inputSchema
    String baseUrl
    String method
    String pathTemplate
    List<String> pathParams = []
    List<String> queryParams = []
    String bodyObjectArg
    List<String> bodyFromArgs
    Map annotations

    Map toDefinition() {
        Map definition = [name: name, description: description, inputSchema: inputSchema]
        if (annotations) definition.annotations = annotations
        return definition
    }
}
