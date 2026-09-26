package com.trevorism.controller

import com.trevorism.auth.TokenManager
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import org.junit.jupiter.api.Test

/**
 * @author tbrooks
 */
class RootControllerTest {

    @Test
    void testRootControllerEndpoints(){
        RootController rootController = new RootController(null, null, null)
        assert rootController.index().getBody().get().contains("/help")
    }

    @Test
    void testRootControllerPing(){
        RootController rootController = new RootController(null, null, null)
        assert rootController.ping() == "pong"
    }

    @Test
    void testRefreshWithoutAValidTokenIsUnauthorized() {
        TokenManager rejectingTokenManager = new TokenManager() {
            @Override
            String authenticate(String authorizationHeader) { null }
        }
        RootController rootController = new RootController(null, null, rejectingTokenManager)

        HttpResponse<Map> response = rootController.refresh("Bearer not-a-token")

        assert HttpStatus.UNAUTHORIZED == response.status()
    }
}
