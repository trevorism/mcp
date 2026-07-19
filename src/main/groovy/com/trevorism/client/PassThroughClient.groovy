package com.trevorism.client

import com.trevorism.http.util.InvalidRequestException
import com.trevorism.https.SecureHttpClient
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Wraps the request-scoped pass-through client so a tool handler can make a downstream call that
 * carries the caller's JWT. Returns the MCP tool-content shape ({content, isError}) and surfaces a
 * downstream status cleanly via {@link InvalidRequestException#getStatusCode()}.
 *
 * MUST be called on the request thread (the pass-through token strategy is request-scoped).
 */
@Singleton
class PassThroughClient {

    private static final Logger log = LoggerFactory.getLogger(PassThroughClient)

    private final SecureHttpClient httpClient

    PassThroughClient(SecureHttpClient passThruSecureHttpClient) {
        this.httpClient = passThruSecureHttpClient
    }

    Map callApi(String method, String url, String body) {
        try {
            String response
            switch (method?.toUpperCase()) {
                case "GET": response = httpClient.get(url); break
                case "POST": response = httpClient.post(url, body ?: ""); break
                case "PUT": response = httpClient.put(url, body ?: ""); break
                case "DELETE": response = httpClient.delete(url); break
                default: return toolError("Unsupported method: ${method}")
            }
            return toolText(response)
        } catch (InvalidRequestException e) {
            // Clean downstream status (e.g. 401 on an expired/absent user token).
            log.warn("Downstream ${e.statusCode} calling ${method} ${url}")
            return toolError("Downstream ${e.statusCode}: ${e.message}")
        } catch (Exception e) {
            log.error("Call failed: ${method} ${url}", e)
            return toolError("Call failed: ${e.message}")
        }
    }

    static Map toolText(String text) {
        [content: [[type: "text", text: text ?: ""]], isError: false]
    }

    static Map toolError(String text) {
        [content: [[type: "text", text: text ?: ""]], isError: true]
    }
}
