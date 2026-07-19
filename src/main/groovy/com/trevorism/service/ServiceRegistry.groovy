package com.trevorism.service

import com.trevorism.http.JsonHttpClient
import com.trevorism.http.util.InvalidRequestException
import com.trevorism.model.ServiceEntry
import groovy.json.JsonSlurper
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.Callable
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
 * Ping cannot be used to *discover* the host: App Engine wildcard routing makes
 * `<anything>.<category>.trevorism.com` answer `pong` (it falls through to the category's default
 * service), so a bogus subdomain looks alive. The authoritative default-vs-subdomain signal is the
 * repo's `app.yaml` `service:` field; the set of default-service repos is small and stable, captured
 * in {@link #DEFAULT_SERVICES}. A single ping of the *constructed* host validates/prunes the entry.
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
    // The project service resolves each category by fetching the repo's deploy.yml from GitHub, so it
    // is the bottleneck; keep concurrency modest to avoid transient failures under a burst.
    private static final int MAX_THREADS = 10
    private static final int CATEGORY_ATTEMPTS = 3

    /** App Engine default-service repos (no `service:` in app.yaml) -> host is `<category>.trevorism.com`. */
    private static final Set<String> DEFAULT_SERVICES = [
            "action", "auth-provider", "cleo-frontend", "data", "homepage", "memo", "project", "testing", "trade"
    ].toSet()

    private final JsonHttpClient http = new JsonHttpClient()
    private final JsonSlurper slurper = new JsonSlurper()

    private volatile List<ServiceEntry> cache = null
    private volatile long cachedAt = 0L

    List<ServiceEntry> listServices(String bearer) {
        List<ServiceEntry> current = cache
        if (current != null && (System.currentTimeMillis() - cachedAt) < TTL_MILLIS) {
            return current
        }
        return refresh(bearer)
    }

    ServiceEntry byName(String name, String bearer) {
        return listServices(bearer).find { it.name == name }
    }

    synchronized List<ServiceEntry> refresh(String bearer) {
        List<String> names = fetchActiveNames()
        log.info("Resolving ${names.size()} services from active")

        List<ServiceEntry> resolved = resolveAll(names, bearer)

        if (resolved.isEmpty() && !names.isEmpty()) {
            throw new IllegalStateException(
                    "Discovery resolved 0 of ${names.size()} services — likely an expired or invalid token.")
        }

        resolved.sort { it.name }
        cache = resolved
        cachedAt = System.currentTimeMillis()
        log.info("Resolved ${resolved.size()}/${names.size()} services")
        return resolved
    }

    void clear() {
        cache = null
        cachedAt = 0L
    }

    private List<ServiceEntry> resolveAll(List<String> names, String bearer) {
        if (names.isEmpty()) return []
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_THREADS, names.size()))
        try {
            List<Future<ServiceEntry>> futures = names.collect { String name ->
                pool.submit({ resolveOne(name, bearer) } as Callable<ServiceEntry>)
            }
            return futures.collect { it.get() }.findAll { it != null }
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
            String category = fetchCategory(name, bearer)
            if (!category || category == "null") {
                return null
            }
            String baseUrl = buildHost(name, category)
            return pingOk(baseUrl) ? new ServiceEntry(name, baseUrl, category) : null
        } catch (Exception e) {
            log.debug("Could not resolve ${name}: ${e.message}")
            return null
        }
    }

    protected String fetchCategory(String name, String bearer) {
        // The project service is GitHub-backed and flaky under load; retry transient failures, but not auth.
        for (int attempt = 1; attempt <= CATEGORY_ATTEMPTS; attempt++) {
            try {
                String body = http.get(PROJECT_SERVICE_URL + name, [Authorization: "Bearer ${bearer}".toString()]).value
                def parsed = slurper.parseText(body)
                return parsed?.dns as String
            } catch (InvalidRequestException e) {
                if (e.statusCode == 401 || e.statusCode == 403) {
                    log.debug("Category lookup unauthorized (${e.statusCode}) for ${name}")
                    return null
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

    /** Canonical host from the platform convention (default service vs. subdomain). */
    static String buildHost(String name, String category) {
        return DEFAULT_SERVICES.contains(name) ?
                "https://${category}.trevorism.com" :
                "https://${name}.${category}.trevorism.com"
    }

    protected boolean pingOk(String baseUrl) {
        // Two ping conventions exist across the platform: some services answer at /ping, others at /api/ping.
        return pingPath("${baseUrl}/ping") || pingPath("${baseUrl}/api/ping")
    }

    private boolean pingPath(String url) {
        try {
            return http.get(url)?.trim() == "pong"
        } catch (Exception ignored) {
            return false
        }
    }
}
