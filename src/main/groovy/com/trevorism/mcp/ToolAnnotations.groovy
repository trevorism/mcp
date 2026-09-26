package com.trevorism.mcp

import groovy.transform.CompileStatic

@CompileStatic
class ToolAnnotations {

    static Map readOnly(String title) {
        [title: title, readOnlyHint: true, openWorldHint: true]
    }

    static Map writes(String title, boolean destructive, boolean idempotent) {
        [title: title, readOnlyHint: false, destructiveHint: destructive, idempotentHint: idempotent, openWorldHint: true]
    }
}
