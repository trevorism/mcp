package com.trevorism.client

import org.junit.jupiter.api.Test

class PassThroughClientTest {

    @Test
    void testRefusesNonTrevorismHostWithoutCallingOut() {
        // A disallowed host must return an isError result immediately (no network, no token sent).
        Map result = new PassThroughClient().callApi("GET", "https://evil.com/steal", null, "secret-token")
        assert result.isError == true
        assert result.content[0].text.contains("Refused")
        assert result.content[0].text.contains("evil.com")
    }

    @Test
    void testRefusesUnsupportedMethodOnAllowedHost() {
        Map result = new PassThroughClient().callApi("TRACE", "https://data.trevorism.com/x", null, "t")
        assert result.isError == true
        assert result.content[0].text.contains("Unsupported method")
    }
}
