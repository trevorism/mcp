package com.trevorism.logs

import com.trevorism.http.util.InvalidRequestException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import java.time.Instant

class CloudLoggingClientTest {

    private static final Instant NOON = Instant.parse("2026-08-16T12:00:00Z")

    private static GoogleTokenProvider fixedToken(String token) {
        new GoogleTokenProvider() {
            @Override
            String getAccessToken() { token }
        }
    }

    /** Replays the given JSON responses in order, recording each request body and header map. */
    private static CloudLoggingClient client(List<String> responses, List requests,
                                             GoogleTokenProvider provider = fixedToken("g-token")) {
        new CloudLoggingClient(provider) {
            @Override
            protected String exchange(String body, Map<String, String> headers) {
                requests << [body: new JsonSlurper().parseText(body), headers: headers]
                return responses[Math.min(requests.size() - 1, responses.size() - 1)]
            }
        }
    }

    private static LogQuery query(Map props = [:]) {
        new LogQuery([project: "trevorism-data"] + props).at(NOON)
    }

    private static String page(List entries, String nextPageToken = null) {
        Map body = [entries: entries]
        if (nextPageToken) {
            body.nextPageToken = nextPageToken
        }
        return JsonOutput.toJson(body)
    }

    @Test
    void testSendsGoogleTokenAndRequestBody() {
        List requests = []
        client([page([])], requests).read(query([module: "event", limit: 10]))
        assert requests[0].headers.Authorization == "Bearer g-token"
        assert requests[0].body.resourceNames == ["projects/trevorism-data"]
        assert requests[0].body.orderBy == "timestamp desc"
        assert requests[0].body.pageSize == 10
        assert requests[0].body.filter.contains('resource.labels.module_id="event"')
    }

    @Test
    void testFlattensTextPayload() {
        String response = page([[timestamp: "2026-08-16T11:59:00Z", severity: "ERROR",
                                 resource : [labels: [module_id: "event"]], textPayload: "kaboom"]])
        Map result = client([response], []).read(query())
        assert result.count == 1
        assert result.entries[0] == [timestamp: "2026-08-16T11:59:00Z", severity: "ERROR",
                                     module   : "event", message: "kaboom"]
    }

    @Test
    void testFlattensJsonPayloadMessageAndDefaultsSeverity() {
        String response = page([[timestamp: "2026-08-16T11:58:00Z", jsonPayload: [message: "structured"]],
                                [timestamp: "2026-08-16T11:57:00Z", jsonPayload: [other: "no message key"]]])
        Map result = client([response], []).read(query())
        assert result.entries[0].message == "structured"
        assert result.entries[0].severity == "DEFAULT"
        assert result.entries[1].message == '{"other":"no message key"}'
    }

    @Test
    void testSummarizesRequestLogProtoPayload() {
        String response = page([[timestamp: "2026-08-16T11:56:00Z",
                                 protoPayload: [method: "GET", resource: "/mcp", status: 200]]])
        Map result = client([response], []).read(query())
        assert result.entries[0].message == "GET /mcp -> 200"
    }

    @Test
    void testFollowsPageTokenWhileEntriesAreEmpty() {
        // An empty page with a nextPageToken means "still scanning", not "no matches".
        List requests = []
        Map result = client([page([], "tok1"), page([], "tok2"), page([[textPayload: "found"]])], requests).read(query())
        assert requests.size() == 3
        assert requests[1].body.pageToken == "tok1"
        assert requests[2].body.pageToken == "tok2"
        assert result.count == 1
        assert result.entries[0].message == "found"
    }

    @Test
    void testStopsWhenAPageHasEntries() {
        List requests = []
        client([page([[textPayload: "first"]], "more"), page([[textPayload: "second"]])], requests).read(query())
        assert requests.size() == 1
    }

    @Test
    void testStopsWhenNoPageTokenRemains() {
        List requests = []
        Map result = client([page([])], requests).read(query())
        assert requests.size() == 1
        assert result.count == 0
    }

    @Test
    void testRateLimitIsRetriedOnce() {
        List attempts = []
        def client = new CloudLoggingClient(fixedToken("g")) {
            @Override
            protected String exchange(String body, Map<String, String> headers) {
                attempts << body
                if (attempts.size() == 1) {
                    throw new InvalidRequestException(new RuntimeException("slow down"), 429)
                }
                return page([[textPayload: "after backoff"]])
            }
            @Override
            protected void pause(long millis) {}
        }
        Map result = client.read(query())
        assert attempts.size() == 2
        assert result.entries[0].message == "after backoff"
    }

    @Test
    void testPersistentRateLimitExplainsTheQuota() {
        def client = new CloudLoggingClient(fixedToken("g")) {
            @Override
            protected String exchange(String body, Map<String, String> headers) {
                throw new InvalidRequestException(new RuntimeException("slow down"), 429)
            }
            @Override
            protected void pause(long millis) {}
        }
        def e = shouldFail { client.read(query()) }
        assert e.message.contains("rate limited")
        assert e.message.contains("retry shortly")
    }

    @Test
    void testForbiddenExplainsTheMissingIamRole() {
        def denying = new CloudLoggingClient(fixedToken("g")) {
            @Override
            protected String exchange(String body, Map<String, String> headers) {
                throw new InvalidRequestException(new RuntimeException("nope"), 403)
            }
        }
        def e = shouldFail { denying.read(query()) }
        assert e.message.contains("roles/logging.viewer")
        assert e.message.contains("trevorism-data")
    }

    @Test
    void testMissingCredentialsSurfaceTheSetupHint() {
        def noCreds = new GoogleTokenProvider() {
            @Override
            String getAccessToken() { throw new IllegalStateException(GoogleTokenProvider.unavailableMessage()) }
        }
        def e = shouldFail { client([page([])], [], noCreds).read(query()) }
        assert e.message.contains("gcloud auth application-default login")
    }

    private static Exception shouldFail(Closure closure) {
        try {
            closure.call()
        } catch (Exception e) {
            return e
        }
        throw new AssertionError("Expected an exception but none was thrown" as Object)
    }
}
