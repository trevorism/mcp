package com.trevorism.gcloud

/**
 * @author tbrooks
 */

this.metaClass.mixin(io.cucumber.groovy.Hooks)
this.metaClass.mixin(io.cucumber.groovy.EN)

String baseUrl = System.getenv("ACCEPTANCE_BASE_URL") ?: "https://mcp.project.trevorism.com"

def contextRootContent
def pingContent

Given(/the mcp application is alive/) { ->
    try {
        new URL("${baseUrl}/ping").text
    } catch (Exception ignored) {
        Thread.sleep(10000)
        new URL("${baseUrl}/ping").text
    }
}

When(/I navigate to {word}/) { String url ->
    contextRootContent = new URL(baseUrl).text
}

When(/I navigate to \/ping on {word}/) { String url ->
    pingContent = new URL("${baseUrl}/ping").text
}

Then(/the API returns a link to the help page/) { ->
    assert contextRootContent
    assert contextRootContent.contains("/help")
}

Then(/pong is returned, to indicate the service is alive/) { ->
    assert pingContent == "pong"
}
