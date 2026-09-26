package com.trevorism.controller

import com.trevorism.auth.TokenManager
import com.trevorism.model.ServiceEntry
import com.trevorism.service.ServiceRegistry
import com.trevorism.service.SpecHarvester
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
    void testRefreshClearsSpecsAndRebuildsWithTheRedeemedAccessToken() {
        List<String> seenTokens = []
        boolean[] specsCleared = [false]
        ServiceRegistry registry = new ServiceRegistry() {
            @Override
            synchronized List<ServiceEntry> refresh(String bearer) {
                seenTokens << bearer
                return [new ServiceEntry("data", "https://data.trevorism.com", "data")]
            }
        }
        SpecHarvester harvester = new SpecHarvester() {
            @Override
            void clear() { specsCleared[0] = true }
        }

        TokenManager tokenManager = new TokenManager() {
            @Override
            protected String redeem(String refreshToken) {
                return refreshToken == "refresh-token" ? "access-token" : null
            }
        }

        Map result = new RootController(registry, harvester, tokenManager).refresh("Bearer refresh-token")

        assert result == [services: 1]
        assert seenTokens == ["access-token"]
        assert specsCleared[0]
    }
}
