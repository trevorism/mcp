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

    /** For test subclasses that override {@link #inspect}. */
    protected ClaimsInspector() {}

    Map inspect(String accessToken) {
        ClaimProperties claims = ClaimsProvider.getClaims(accessToken, propertiesProvider.getProperty("signingKey"))
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
}
