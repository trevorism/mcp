package com.trevorism.mcp.curated

import groovy.transform.CompileStatic

/**
 * Declarative description of one curated (first-class) MCP tool: a named tool with a typed schema that
 * maps to a single downstream HTTP call. Adding a tool is one entry in {@link CuratedToolRegistry}.
 */
@CompileStatic
class CuratedTool {
    String name
    String description
    Map inputSchema
    String baseUrl
    String method
    String pathTemplate            // e.g. "/object/{kind}/{id}"
    List<String> pathParams = []   // names substituted into pathTemplate
    List<String> queryParams = []  // optional query args appended when present (e.g. ["datasource"])
    String bodyObjectArg           // an arg whose value IS the JSON body object (or null)
    List<String> bodyFromArgs      // arg names assembled into the JSON body map (or null)

    Map toDefinition() {
        [name: name, description: description, inputSchema: inputSchema]
    }
}
