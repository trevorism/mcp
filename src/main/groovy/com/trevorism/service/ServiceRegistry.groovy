package com.trevorism.service

import com.trevorism.http.JsonHttpClient
import com.trevorism.http.util.InvalidRequestException
import com.trevorism.model.ServiceEntry
import groovy.json.JsonSlurper
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Discovers Trevorism services and resolves each to a canonical base URL.
 *
 * Names come from the unsecured `active` endpoint; each service's category (dns) comes from the
 * @Secure `project/service/{name}` endpoint (so a caller bearer token is required). The host is then
 * built from the platform convention: App Engine DEFAULT services live at `<category>.trevorism.com`,
 * everything else at `<name>.<category>.trevorism.com`.
 *
 * Discovery never pings. App Engine wildcard routing makes `<anything>.<category>.trevorism.com`
 * answer `pong` (it falls through to the category's default service), so a ping cannot discover a
 * host, nor tell a real subdomain from a bogus one — it only wakes a scale-to-zero instance and
 * bills a cold start. The authoritative default-vs-subdomain signal is the repo's `app.yaml`
 * `service:` field; that set is small and stable, captured in {@link #DEFAULT_SERVICES}. Liveness is
 * the caller's business, via the `ping_service` tool.
 *
 * Category lookups use a plain client with an explicit Authorization header (NOT the request-scoped
 * pass-through client, which would not resolve the token off the request thread) and run on a sized
 * pool so ~34 services resolve in a couple of seconds.
 */
@Singleton
class ServiceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ServiceRegistry)

    private static final String ACTIVE_URL = "https://active.project.trevorism.com/api/active/service"
    private static final String PROJECT_SERVICE_URL = "https://project.trevorism.com/project/service/"
    private static final long TTL_MILLIS = 3600_000L
    private static final int MAX_THREADS = 10
    private static final int CATEGORY_ATTEMPTS = 3

    private static final Set<String> DEFAULT_SERVICES = [
            "action", "auth-provider", "cleo-frontend", "data", "homepage", "memo", "project", "testing", "trade"
    ].toSet()

    private final JsonHttpClient http = new JsonHttpClient()
    private final JsonSlurper slurper = new JsonSlurper()

    private volatile List<ServiceEntry> cache = null
    private volatile long cachedAt = 0L

    List<ServiceEntry> listServices(String bearer) {
        List<ServiceEntry> current = freshCache()
        return current != null ? current : refreshIfStale(bearer)
    }

    ServiceEntry byName(String name, String bearer) {
        ServiceEntry cached = freshCache()?.find { it.name == name }
        return cached ?: resolveOne(name, bearer)
    }

    private List<ServiceEntry> freshCache() {
        List<ServiceEntry> current = cache
        boolean fresh = current != null && (System.currentTimeMillis() - cachedAt) < TTL_MILLIS
        return fresh ? current : null
    }

    private synchronized List<ServiceEntry> refreshIfStale(String bearer) {
        List<ServiceEntry> current = freshCache()
        return current != null ? current : refresh(bearer)
    }

    synchronized List<ServiceEntry> refresh(String bearer) {
        List<String> names = fetchActiveNames()
        log.info("Resolving ${names.size()} services from active")

        Discovery discovery = resolveAll(names, bearer)
        List<ServiceEntry> resolved = discovery.entries

        if (resolved.isEmpty() && !names.isEmpty()) {
            throw new IllegalStateException(
                    "Discovery resolved 0 of ${names.size()} services — likely an expired or invalid token.")
        }

        resolved.sort { it.name }
        if (discovery.denied) {
            log.warn("Resolved ${resolved.size()}/${names.size()} services, but some lookups were denied for this caller; not caching")
            return resolved
        }
        cache = resolved
        cachedAt = System.currentTimeMillis()
        log.info("Resolved ${resolved.size()}/${names.size()} services")
        return resolved
    }

    private Discovery resolveAll(List<String> names, String bearer) {
        Discovery discovery = new Discovery()
        if (names.isEmpty()) return discovery
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_THREADS, names.size()))
        try {
            List<Future<ServiceEntry>> futures = names.collect { String name ->
                pool.submit({ lookup(name, bearer) } as Callable<ServiceEntry>)
            }
            futures.each { Future<ServiceEntry> future ->
                try {
                    ServiceEntry entry = future.get()
                    if (entry) discovery.entries << entry
                } catch (ExecutionException e) {
                    if (!(e.cause instanceof LookupDeniedException)) throw e
                    discovery.denied = true
                }
            }
            return discovery
        } finally {
            pool.shutdown()
        }
    }

    protected List<String> fetchActiveNames() {
        def parsed = slurper.parseText(http.get(ACTIVE_URL))
        return parsed.collect { it.name as String }.findAll { it }
    }

    private ServiceEntry resolveOne(String name, String bearer) {
        try {
            return lookup(name, bearer)
        } catch (LookupDeniedException e) {
            log.debug(e.message)
            return null
        }
    }

    private ServiceEntry lookup(String name, String bearer) {
        try {
            String category = fetchCategory(name, bearer)
            if (!category || category == "null") {
                return null
            }
            return new ServiceEntry(name, buildHost(name, category), category)
        } catch (LookupDeniedException e) {
            throw e
        } catch (Exception e) {
            log.debug("Could not resolve ${name}: ${e.message}")
            return null
        }
    }

    protected String fetchCategory(String name, String bearer) {
        for (int attempt = 1; attempt <= CATEGORY_ATTEMPTS; attempt++) {
            try {
                String body = http.get(PROJECT_SERVICE_URL + name, [Authorization: "Bearer ${bearer}".toString()]).value
                def parsed = slurper.parseText(body)
                return parsed?.dns as String
            } catch (InvalidRequestException e) {
                if (e.statusCode == 401 || e.statusCode == 403) {
                    throw new LookupDeniedException(name, e.statusCode)
                }
                log.debug("Category lookup for ${name} failed (attempt ${attempt}, ${e.statusCode})")
            } catch (Exception e) {
                log.debug("Category lookup for ${name} failed (attempt ${attempt}): ${e.message}")
            }
            sleepQuietly(150L * attempt)
        }
        return null
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }

    static String buildHost(String name, String category) {
        return DEFAULT_SERVICES.contains(name) ?
                "https://${category}.trevorism.com" :
                "https://${name}.${category}.trevorism.com"
    }

    static class LookupDeniedException extends RuntimeException {
        LookupDeniedException(String name, int status) {
            super("Category lookup for ${name} denied (${status})".toString())
        }
    }

    private static class Discovery {
        List<ServiceEntry> entries = []
        boolean denied
    }
}
