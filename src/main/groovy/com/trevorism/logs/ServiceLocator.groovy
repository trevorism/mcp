package com.trevorism.logs

import com.trevorism.model.ServiceEntry
import com.trevorism.service.ServiceRegistry
import jakarta.inject.Singleton

/**
 * Maps a Trevorism service name onto the GCP project and App Engine module its logs live in.
 *
 * {@link ServiceRegistry} already resolves each service's category (the `dns` value), which is also the
 * project suffix by platform convention: category `data` -> project `trevorism-data`. Services deployed
 * as their category's App Engine default service log under module `default`; everything else logs under
 * its own name.
 *
 * When the name cannot be resolved this returns null rather than guessing — the tool then asks the caller
 * for an explicit project, which is better than silently reading the wrong service's logs.
 */
@Singleton
class ServiceLocator {

    /** Categories seen across the platform's deploy workflows; each maps to a `trevorism-<category>` project. */
    static final Set<String> KNOWN_CATEGORIES = [
            "action", "auth", "cleo", "data", "draw", "gcloud", "memo", "project", "testing", "trade", "trevorism"
    ].toSet()

    private final ServiceRegistry registry

    ServiceLocator(ServiceRegistry registry) {
        this.registry = registry
    }

    /** {@code [project: 'trevorism-data', module: 'event']}, or null when the service is unknown. */
    Map locate(String serviceName, String bearer) {
        ServiceEntry entry = registry.byName(serviceName, bearer)
        if (!entry?.category) {
            return null
        }
        return [project: projectFor(entry.category), module: moduleFor(entry)]
    }

    static String projectFor(String category) {
        return category == "trevorism" ? "trevorism" : "trevorism-${category}".toString()
    }

    /**
     * A service reachable at the bare category host (e.g. `testing.trevorism.com`) is that category's
     * App Engine default service; a subdomain host (`event.data.trevorism.com`) is a named module.
     */
    static String moduleFor(ServiceEntry entry) {
        String host = hostOf(entry.baseUrl)
        if (!host) {
            return entry.name
        }
        return host.split("\\.").length <= 3 ? "default" : host.split("\\.")[0]
    }

    private static String hostOf(String url) {
        try {
            return new URI(url).host?.toLowerCase()
        } catch (Exception ignored) {
            return null
        }
    }
}
