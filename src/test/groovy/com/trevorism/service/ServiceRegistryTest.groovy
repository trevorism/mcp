package com.trevorism.service

import com.trevorism.model.ServiceEntry
import org.junit.jupiter.api.Test

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

class ServiceRegistryTest {

    /** Registry whose HTTP boundary is faked: names + categories are fixed. */
    private static ServiceRegistry fakeRegistry(List<String> names, Map<String, String> categories) {
        new ServiceRegistry() {
            @Override
            protected List<String> fetchActiveNames() { names }
            @Override
            protected String fetchCategory(String name, String bearer) { categories[name] }
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
                ["data", "event", "auth-provider", "ghost"],
                [data: "data", event: "data", "auth-provider": "auth", ghost: null])

        List<ServiceEntry> entries = reg.refresh("tok")

        assert entries.collect { it.name } == ["auth-provider", "data", "event"]  // sorted; ghost dropped
        assert entries.find { it.name == "event" }.baseUrl == "https://event.data.trevorism.com"
        assert entries.find { it.name == "auth-provider" }.baseUrl == "https://auth.trevorism.com"
    }

    @Test
    void testRefreshThrowsWhenAllUnresolved() {
        def reg = fakeRegistry(["data"], [data: null])
        try {
            reg.refresh("expired")
            assert false: "expected failure"
        } catch (IllegalStateException e) {
            assert e.message.contains("expired or invalid token")
        }
    }

    @Test
    void testByNameResolvesOneServiceWithoutDiscoveringThePlatform() {
        int[] activeFetches = [0]
        def reg = new ServiceRegistry() {
            @Override
            protected List<String> fetchActiveNames() { activeFetches[0]++; ["mcp", "event", "data"] }
            @Override
            protected String fetchCategory(String name, String bearer) { name == "mcp" ? "project" : "data" }
        }

        ServiceEntry entry = reg.byName("mcp", "tok")

        assert entry.baseUrl == "https://mcp.project.trevorism.com"
        assert entry.category == "project"
        assert activeFetches[0] == 0
    }

    @Test
    void testByNameReturnsNullWhenTheServiceDoesNotResolve() {
        def reg = fakeRegistry(["data"], [nope: null])
        assert reg.byName("nope", "tok") == null
    }

    @Test
    void testByNameIsServedFromAFreshCache() {
        int[] categoryFetches = [0]
        def reg = new ServiceRegistry() {
            @Override
            protected List<String> fetchActiveNames() { ["data"] }
            @Override
            protected String fetchCategory(String name, String bearer) { categoryFetches[0]++; "data" }
        }
        reg.listServices("tok")

        assert reg.byName("data", "tok").baseUrl == "https://data.trevorism.com"
        assert categoryFetches[0] == 1
    }

    @Test
    void testCacheIsReturnedWithinTtl() {
        int[] fetches = [0]
        def reg = new ServiceRegistry() {
            @Override
            protected List<String> fetchActiveNames() { fetches[0]++; ["data"] }
            @Override
            protected String fetchCategory(String name, String bearer) { "data" }
        }
        reg.listServices("tok")
        reg.listServices("tok")
        assert fetches[0] == 1  // second call served from the in-memory cache, not re-resolved
    }

    @Test
    void testConcurrentCallersOnAStaleCacheTriggerASingleRefresh() {
        AtomicInteger fetches = new AtomicInteger()
        def reg = new ServiceRegistry() {
            @Override
            protected List<String> fetchActiveNames() { fetches.incrementAndGet(); Thread.sleep(100); ["data"] }
            @Override
            protected String fetchCategory(String name, String bearer) { "data" }
        }
        ExecutorService pool = Executors.newFixedThreadPool(5)
        try {
            List<Future> futures = (1..5).collect { pool.submit({ reg.listServices("tok") } as Callable) }
            futures.each { it.get() }
        } finally {
            pool.shutdown()
        }
        assert fetches.get() == 1
    }

    @Test
    void testExplicitRefreshAlwaysRebuilds() {
        AtomicInteger fetches = new AtomicInteger()
        def reg = new ServiceRegistry() {
            @Override
            protected List<String> fetchActiveNames() { fetches.incrementAndGet(); ["data"] }
            @Override
            protected String fetchCategory(String name, String bearer) { "data" }
        }
        reg.listServices("tok")
        reg.refresh("tok")
        assert fetches.get() == 2
    }
}
