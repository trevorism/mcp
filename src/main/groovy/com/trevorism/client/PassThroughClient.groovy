package com.trevorism.client

import com.trevorism.http.JsonHttpClient
import com.trevorism.http.util.InvalidRequestException
import com.trevorism.util.HostAllowlist
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Makes a downstream call carrying the caller's (resolved) access token, and returns the MCP
 * tool-content shape ({content, isError}). A downstream status is surfaced cleanly via
 * {@link InvalidRequestException#getStatusCode()}.
 *
 * The access token is passed in explicitly (resolved by TokenManager on the request thread) rather than
 * read from a request-scoped bean — simpler and safe to use from any thread.
 */
@Singleton
class PassThroughClient {

    private static final Logger log = LoggerFactory.getLogger(PassThroughClient)

    private final JsonHttpClient http = new JsonHttpClient()

    Map callApi(String method, String url, String body, String accessToken) {
        // Never forward the caller's token to a non-Trevorism host (confused-deputy / exfiltration guard).
        if (!HostAllowlist.isAllowed(url)) {
            log.warn("Refusing to forward token to non-Trevorism host: ${url}")
            return toolError("Refused: '${url}' is not a Trevorism (*.trevorism.com) host")
        }
        Map<String, String> headers = ["Authorization": "Bearer ${accessToken}".toString()]
        try {
            String response
            switch (method?.toUpperCase()) {
                case "GET": response = http.get(url, headers).value; break
                case "POST": response = http.post(url, body ?: "", headers).value; break
                case "PUT": response = http.put(url, body ?: "", headers).value; break
                case "DELETE": response = http.delete(url, headers).value; break
                default: return toolError("Unsupported method: ${method}")
            }
            return toolText(response)
        } catch (InvalidRequestException e) {
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
