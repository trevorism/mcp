package com.trevorism.util

import org.junit.jupiter.api.Test

class HostAllowlistTest {

    @Test
    void testAllowsTrevorismHosts() {
        assert HostAllowlist.isAllowed("https://trevorism.com/x")
        assert HostAllowlist.isAllowed("https://data.trevorism.com/object")
        assert HostAllowlist.isAllowed("https://event.data.trevorism.com/event/topic")
        assert HostAllowlist.isAllowed("https://TESTING.TREVORISM.COM/api/suite")   // case-insensitive
    }

    @Test
    void testRejectsNonTrevorismHosts() {
        assert !HostAllowlist.isAllowed("https://evil.com/x")
        assert !HostAllowlist.isAllowed("https://eviltrevorism.com/x")          // no dot boundary
        assert !HostAllowlist.isAllowed("https://data.trevorism.com.evil.com/x") // suffix trick
        assert !HostAllowlist.isAllowed("https://data.trevorism.com@evil.com/x") // userinfo trick
        assert !HostAllowlist.isAllowed("not a url")
        assert !HostAllowlist.isAllowed(null)
    }
}
