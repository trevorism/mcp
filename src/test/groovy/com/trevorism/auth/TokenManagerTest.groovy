package com.trevorism.auth

import com.trevorism.http.util.InvalidRequestException
import org.junit.jupiter.api.Test

class TokenManagerTest {

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
    void testNotRedeemableBearerIsCachedAndNotRetried() {
        List<String> seen = []
        def tm = withRedeem(seen) { throw new InvalidRequestException(new RuntimeException("nope"), 400) }
        assert tm.resolveAccessToken("Bearer plain-access-token") == "plain-access-token"
        assert tm.resolveAccessToken("Bearer plain-access-token") == "plain-access-token"
        assert tm.resolveAccessToken("Bearer plain-access-token") == "plain-access-token"
        assert seen.size() == 1
    }

    @Test
    void testEmptyRedeemResponseIsCachedAsNotRedeemable() {
        List<String> seen = []
        def tm = withRedeem(seen) { null }
        assert tm.resolveAccessToken("rt") == "rt"
        assert tm.resolveAccessToken("rt") == "rt"
        assert seen.size() == 1
    }

    @Test
    void testServerErrorIsNotCachedSoItRetries() {
        List<String> seen = []
        def tm = withRedeem(seen) { throw new InvalidRequestException(new RuntimeException("boom"), 503) }
        assert tm.resolveAccessToken("rt") == "rt"
        assert tm.resolveAccessToken("rt") == "rt"
        assert seen.size() == 2
    }

    @Test
    void testTransientFailureIsNotCachedSoItRetries() {
        List<String> seen = []
        def tm = withRedeem(seen) { throw new IOException("connection reset") }
        assert tm.resolveAccessToken("rt") == "rt"
        assert tm.resolveAccessToken("rt") == "rt"
        assert seen.size() == 2
    }

    @Test
    void testNotRedeemableCacheDoesNotBlockADifferentBearer() {
        List<String> seen = []
        def tm = withRedeem(seen) { it == "good-rt" ? "access-9" : null }
        assert tm.resolveAccessToken("bad-rt") == "bad-rt"
        assert tm.resolveAccessToken("good-rt") == "access-9"
        assert seen == ["bad-rt", "good-rt"]
    }

    private static TokenManager authenticating(Set<String> validTokens, Closure<String> redeemImpl) {
        ClaimsInspector inspector = new ClaimsInspector() {
            @Override
            boolean isValid(String accessToken) { validTokens.contains(accessToken) }
        }
        new TokenManager(inspector) {
            @Override
            protected String redeem(String refreshToken) { redeemImpl(refreshToken) }
        }
    }

    @Test
    void testAuthenticateReturnsTheRedeemedAccessTokenWhenValid() {
        def tm = authenticating(["access-1"] as Set) { "access-1" }
        assert tm.authenticate("Bearer rt") == "access-1"
    }

    @Test
    void testAuthenticateAcceptsAValidPlainAccessToken() {
        def tm = authenticating(["plain-access"] as Set) { throw new InvalidRequestException(new RuntimeException("nope"), 400) }
        assert tm.authenticate("Bearer plain-access") == "plain-access"
    }

    @Test
    void testAuthenticateRejectsAnUnverifiableBearer() {
        def tm = authenticating([] as Set) { throw new InvalidRequestException(new RuntimeException("nope"), 400) }
        assert tm.authenticate("Bearer not-a-token") == null
    }

    @Test
    void testAuthenticateRejectsAMissingHeader() {
        def tm = authenticating(["x"] as Set) { "x" }
        assert tm.authenticate(null) == null
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
