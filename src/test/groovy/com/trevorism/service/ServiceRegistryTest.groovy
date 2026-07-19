package com.trevorism.service

import com.trevorism.model.ServiceEntry
import org.junit.jupiter.api.Test

class ServiceRegistryTest {

    /** Registry whose HTTP boundary is faked: names + categories are fixed, ping succeeds only for given hosts. */
    private static ServiceRegistry fakeRegistry(List<String> names, Map<String, String> categories, Set<String> pingable) {
        new ServiceRegistry() {
            @Override
            protected List<String> fetchActiveNames() { names }
            @Override
            protected String fetchCategory(String name, String bearer) { categories[name] }
            @Override
            protected boolean pingOk(String baseUrl) { pingable.contains(baseUrl) }
        }
    }

    @Test
    void testBuildHostSubdomainForNonDefaultService() {
        assert ServiceRegistry.buildHost("event", "data") == "https://event.data.trevorism.com"
        assert ServiceRegistry.buildHost("active", "project") == "https://active.project.trevorism.com"
    }

    @Test
    void testBuildHostRootForDefaultService() {
        // Default services live at the category root even when name != category (auth-provider -> auth.trevorism.com).
        assert ServiceRegistry.buildHost("data", "data") == "https://data.trevorism.com"
        assert ServiceRegistry.buildHost("auth-provider", "auth") == "https://auth.trevorism.com"
        assert ServiceRegistry.buildHost("testing", "testing") == "https://testing.trevorism.com"
    }

    @Test
    void testRefreshResolvesCanonicalHostsAndDropsUnresolved() {
        def reg = fakeRegistry(
                ["data", "event", "auth-provider", "ghost", "dead"],
                [data: "data", event: "data", "auth-provider": "auth", ghost: null, dead: "data"],
                // note: 'dead' has a category but its canonical host does not ping -> pruned
                ["https://data.trevorism.com",
                 "https://event.data.trevorism.com",
                 "https://auth.trevorism.com"] as Set)

        List<ServiceEntry> entries = reg.refresh("tok")

        assert entries.collect { it.name } == ["auth-provider", "data", "event"]  // sorted; ghost + dead dropped
        assert entries.find { it.name == "event" }.baseUrl == "https://event.data.trevorism.com"
        assert entries.find { it.name == "auth-provider" }.baseUrl == "https://auth.trevorism.com"
    }

    @Test
    void testRefreshThrowsWhenAllUnresolved() {
        def reg = fakeRegistry(["data"], [data: null], [] as Set)
        try {
            reg.refresh("expired")
            assert false: "expected failure"
        } catch (IllegalStateException e) {
            assert e.message.contains("expired or invalid token")
        }
    }

    @Test
    void testCacheIsReturnedWithinTtl() {
        int[] fetches = [0]
        def reg = new ServiceRegistry() {
            @Override
            protected List<String> fetchActiveNames() { fetches[0]++; ["data"] }
            @Override
            protected String fetchCategory(String name, String bearer) { "data" }
            @Override
            protected boolean pingOk(String baseUrl) { baseUrl == "https://data.trevorism.com" }
        }
        reg.listServices("tok")
        reg.listServices("tok")
        assert fetches[0] == 1  // second call served from the in-memory cache, not re-resolved
    }
}
