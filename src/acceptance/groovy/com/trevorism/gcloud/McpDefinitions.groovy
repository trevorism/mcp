package com.trevorism.gcloud

import com.trevorism.http.JsonHttpClient
import com.trevorism.https.AppClientSecureHttpClient
import com.trevorism.https.SecureHttpClient
import io.cucumber.groovy.EN
import io.cucumber.groovy.Hooks

this.metaClass.mixin(Hooks)
this.metaClass.mixin(EN)

def MCP_URL = "https://mcp.project.trevorism.com/mcp"

def response
def rejected = false

When(/an authenticated initialize request is sent to the mcp endpoint/) { ->
    SecureHttpClient client = new AppClientSecureHttpClient()
    response = client.post(MCP_URL, '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}')
}

Then(/the response advertises the mcp server and protocol version/) { ->
    assert response.contains('"protocolVersion"')
    assert response.contains('trevorism-mcp')
}

When(/an authenticated tools list request is sent to the mcp endpoint/) { ->
    SecureHttpClient client = new AppClientSecureHttpClient()
    response = client.post(MCP_URL, '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')
}

Then(/the meta tools and curated tools are present/) { ->
    ["list_trevorism_services", "describe_service", "whoami", "call_trevorism_api",
     "get_object", "run_test_suite"].each { assert response.contains("\"${it}\"") }
}

When(/an unauthenticated request is sent to the mcp endpoint/) { ->
    try {
        new JsonHttpClient().post(MCP_URL, '{"jsonrpc":"2.0","id":4,"method":"initialize","params":{}}')
        rejected = false
    } catch (Exception ignored) {
        rejected = true
    }
}

Then(/the request is rejected/) { ->
    assert rejected
}
