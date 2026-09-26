package com.trevorism.auth

import com.trevorism.ClaimProperties
import com.trevorism.ClaimsProvider
import com.trevorism.micronaut.PropertiesBean
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class ClaimsInspector {

    private PropertiesBean propertiesProvider

    @Inject
    ClaimsInspector(PropertiesBean propertiesProvider) {
        this.propertiesProvider = propertiesProvider
    }

    protected ClaimsInspector() {}

    boolean isValid(String accessToken) {
        try {
            ClaimsProvider.getClaims(accessToken, signingKey())
            return true
        } catch (Exception ignored) {
            return false
        }
    }

    Map inspect(String accessToken) {
        ClaimProperties claims = ClaimsProvider.getClaims(accessToken, signingKey())
        return [
                subject    : claims.subject,
                id         : claims.id,
                role       : claims.role,
                permissions: claims.permissions,
                tenant     : claims.tenant,
                type       : claims.type,
                audience   : claims.audience as List,
                issuer     : claims.issuer
        ]
    }

    protected String signingKey() {
        propertiesProvider.getProperty("signingKey")
    }
}
