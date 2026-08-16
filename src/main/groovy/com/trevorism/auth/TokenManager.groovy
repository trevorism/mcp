package com.trevorism.auth

import com.trevorism.http.JsonHttpClient
import com.trevorism.http.util.InvalidRequestException
import groovy.json.JsonOutput
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap

@Singleton
class TokenManager {

    private static final Logger log = LoggerFactory.getLogger(TokenManager)
    private static final String REDEEM_URL = "https://auth.trevorism.com/token/refresh/redeem"
    private static final long ACCESS_TTL_MILLIS = 13 * 60 * 1000L
    private static final long NOT_REDEEMABLE_TTL_MILLIS = 60 * 60 * 1000L

    private final JsonHttpClient http = new JsonHttpClient()
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>()

    String resolveAccessToken(String authorizationHeader) {
        String bearer = strip(authorizationHeader)
        if (!bearer) {
            return null
        }

        CachedToken cached = cache.get(bearer)
        if (cached && !cached.isExpired()) {
            return cached.accessToken ?: bearer
        }

        try {
            String accessToken = redeem(bearer)
            if (accessToken) {
                cache.put(bearer, new CachedToken(accessToken, System.currentTimeMillis() + ACCESS_TTL_MILLIS))
                evictExpired()
                log.info("Redeemed a refresh token for a fresh access token (cached ${ACCESS_TTL_MILLIS / 60000}m)")
                return accessToken
            }
            rememberNotRedeemable(bearer)
        } catch (InvalidRequestException e) {
            if (e.statusCode >= 400 && e.statusCode < 500) {
                rememberNotRedeemable(bearer)
            }
            log.debug("Redeem failed (${e.statusCode}); treating bearer as an access token")
        } catch (Exception e) {
            log.warn("Unexpected error redeeming refresh token: ${e.message}")
        }
        return bearer
    }

    private void rememberNotRedeemable(String bearer) {
        cache.put(bearer, new CachedToken(null, System.currentTimeMillis() + NOT_REDEEMABLE_TTL_MILLIS))
        evictExpired()
    }

    protected String redeem(String refreshToken) {
        String body = JsonOutput.toJson([refreshToken: refreshToken])
        return http.post(REDEEM_URL, body)?.trim()
    }

    private void evictExpired() {
        cache.entrySet().removeIf { it.value.isExpired() }
    }

    private static String strip(String header) {
        if (!header) return null
        String h = header.trim()
        if (h.toLowerCase().startsWith("bearer")) {
            h = h.substring("bearer".length()).trim()
        }
        return h ?: null
    }

    private static class CachedToken {
        final String accessToken
        final long expiresAt

        CachedToken(String accessToken, long expiresAt) {
            this.accessToken = accessToken
            this.expiresAt = expiresAt
        }

        boolean isExpired() {
            return System.currentTimeMillis() >= expiresAt
        }
    }
}
