package com.trevorism.auth

import com.trevorism.http.util.InvalidRequestException
import org.junit.jupiter.api.Test

class TokenManagerTest {

    /** TokenManager whose redeem boundary is faked, counting invocations. */
    private static TokenManager withRedeem(List<String> seen, Closure<String> impl) {
        new TokenManager() {
            @Override
            protected String redeem(String refreshToken) {
                seen << refreshToken
                return impl(refreshToken)
            }
        }
    }

    @Test
    void testRedeemsRefreshTokenAndStripsBearerPrefix() {
        List<String> seen = []
        def tm = withRedeem(seen) { "access-123" }
        assert tm.resolveAccessToken("Bearer my-refresh") == "access-123"
        assert seen == ["my-refresh"]   // "Bearer " stripped before redeem
    }

    @Test
    void testCacheAvoidsSecondRedeemWithinTtl() {
        List<String> seen = []
        def tm = withRedeem(seen) { "access-1" }
        assert tm.resolveAccessToken("rt") == "access-1"
        assert tm.resolveAccessToken("rt") == "access-1"
        assert seen.size() == 1   // second call served from cache
    }

    @Test
    void testFallsBackToBearerWhenRedeemFails() {
        List<String> seen = []
        def tm = withRedeem(seen) { throw new InvalidRequestException(new RuntimeException("nope"), 400) }
        // A plain access token isn't redeemable -> use it directly (15-min mode).
        assert tm.resolveAccessToken("Bearer plain-access-token") == "plain-access-token"
    }

    @Test
    void testNullOrEmptyHeaderReturnsNull() {
        def tm = withRedeem([]) { "x" }
        assert tm.resolveAccessToken(null) == null
        assert tm.resolveAccessToken("") == null
        assert tm.resolveAccessToken("   ") == null
        assert tm.resolveAccessToken("Bearer ") == null   // scheme with no credential
    }
}
