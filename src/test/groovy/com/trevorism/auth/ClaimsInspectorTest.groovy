package com.trevorism.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Encoders
import org.junit.jupiter.api.Test

import javax.crypto.SecretKey

class ClaimsInspectorTest {

    private static final SecretKey KEY = Jwts.SIG.HS256.key().build()
    private static final SecretKey OTHER_KEY = Jwts.SIG.HS256.key().build()

    private static ClaimsInspector inspectorWithKey(SecretKey key) {
        String encoded = Encoders.BASE64.encode(key.encoded)
        new ClaimsInspector() {
            @Override
            protected String signingKey() { encoded }
        }
    }

    private static String token(SecretKey key, Date expiration) {
        Jwts.builder().subject("me").claim("role", "user").expiration(expiration).signWith(key).compact()
    }

    private static Date minutesFromNow(int minutes) {
        new Date(System.currentTimeMillis() + minutes * 60_000L)
    }

    @Test
    void testTokenSignedWithTheSigningKeyIsValid() {
        assert inspectorWithKey(KEY).isValid(token(KEY, minutesFromNow(10)))
    }

    @Test
    void testTokenSignedWithAnotherKeyIsInvalid() {
        assert !inspectorWithKey(KEY).isValid(token(OTHER_KEY, minutesFromNow(10)))
    }

    @Test
    void testExpiredTokenIsInvalid() {
        assert !inspectorWithKey(KEY).isValid(token(KEY, minutesFromNow(-10)))
    }

    @Test
    void testGarbageIsInvalid() {
        assert !inspectorWithKey(KEY).isValid("not-a-token")
        assert !inspectorWithKey(KEY).isValid(null)
    }

    @Test
    void testInspectDecodesClaims() {
        Map claims = inspectorWithKey(KEY).inspect(token(KEY, minutesFromNow(10)))
        assert claims.subject == "me"
        assert claims.role == "user"
    }
}
