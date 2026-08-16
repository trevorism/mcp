package com.trevorism.logs

import com.trevorism.model.ServiceEntry
import com.trevorism.service.ServiceRegistry
import jakarta.inject.Singleton

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
