package com.trevorism.logs

import com.trevorism.http.JsonHttpClient
import com.trevorism.http.util.InvalidRequestException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Singleton
class CloudLoggingClient {

    private static final Logger log = LoggerFactory.getLogger(CloudLoggingClient)
    private static final String ENTRIES_URL = "https://logging.googleapis.com/v2/entries:list"
    private static final int MAX_EMPTY_PAGES = 3
    private static final long RATE_LIMIT_BACKOFF_MILLIS = 2000L

    private final GoogleTokenProvider tokenProvider
    private final JsonHttpClient http = new JsonHttpClient()
    private final JsonSlurper slurper = new JsonSlurper()

    CloudLoggingClient(GoogleTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider
    }

    Map read(LogQuery query) {
        Map request = query.toRequest()
        log.info("Reading logs from ${query.project}: ${request.filter}")
        List entries = []
        String pageToken = null

        for (int page = 0; page < MAX_EMPTY_PAGES; page++) {
            Map body = pageToken ? request + [pageToken: pageToken] : request
            Map response = post(body, query.project)
            entries.addAll((response.entries ?: []) as List)
            pageToken = response.nextPageToken as String
            if (entries || !pageToken) {
                break
            }
        }

        return [
                project: query.project,
                filter : request.filter,
                count  : entries.size(),
                entries: entries.collect { flatten(it as Map) }
        ]
    }

    private Map post(Map body, String project) {
        String payload = JsonOutput.toJson(body)
        try {
            return send(payload)
        } catch (InvalidRequestException e) {
            if (e.statusCode == 429) {
                log.info("Rate limited by Cloud Logging; retrying once after ${RATE_LIMIT_BACKOFF_MILLIS}ms")
                pause(RATE_LIMIT_BACKOFF_MILLIS)
                try {
                    return send(payload)
                } catch (InvalidRequestException retry) {
                    throw new IllegalStateException(explain(retry.statusCode, project), retry)
                }
            }
            throw new IllegalStateException(explain(e.statusCode, project), e)
        }
    }

    private Map send(String payload) {
        Map<String, String> headers = ["Authorization": "Bearer ${tokenProvider.getAccessToken()}".toString()]
        String response = exchange(payload, headers)
        return (slurper.parseText(response ?: "{}") ?: [:]) as Map
    }

    protected void pause(long millis) {
        try {
            Thread.sleep(millis)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }

    protected String exchange(String body, Map<String, String> headers) {
        return http.post(ENTRIES_URL, body, headers).value
    }

    private static String explain(int status, String project) {
        if (status == 403 || status == 401) {
            return "Cloud Logging denied access to project '${project}' (${status}). The identity running " +
                    "this service needs roles/logging.viewer there — deployed that is " +
                    "trevorism-project@appspot.gserviceaccount.com."
        }
        if (status == 404) {
            return "Project '${project}' was not found by Cloud Logging."
        }
        if (status == 429) {
            return "Cloud Logging rate limited this read of '${project}' (429). Its read quota recovers " +
                    "within a minute — narrow the time window or retry shortly."
        }
        return "Cloud Logging returned ${status} for project '${project}'."
    }

    private static Map flatten(Map entry) {
        return [
                timestamp: entry.timestamp,
                severity : entry.severity ?: "DEFAULT",
                module   : ((entry.resource as Map)?.labels as Map)?.module_id,
                message  : messageOf(entry)
        ]
    }

    private static Object messageOf(Map entry) {
        if (entry.textPayload) {
            return entry.textPayload
        }
        Map json = entry.jsonPayload as Map
        if (json) {
            return json.message ?: JsonOutput.toJson(json)
        }
        Map proto = entry.protoPayload as Map
        if (proto) {
            return proto.status ? "${proto.method ?: ''} ${proto.resource ?: ''} -> ${proto.status}".trim()
                    : JsonOutput.toJson(proto)
        }
        return ""
    }
}
