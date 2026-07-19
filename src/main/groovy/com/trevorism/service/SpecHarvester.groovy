package com.trevorism.service

import com.trevorism.http.JsonHttpClient
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Harvests a service's OpenAPI spec and summarizes it to a token-cheap operation list.
 *
 * Flow: GET {base}/swagger-ui/index.html -> parse the `url: '.../swagger/<title>-<ver>.yml'`
 * reference -> fetch that YAML -> summarize {title, version, operations:[{method, path, summary, secured}]}.
 * Cached per base URL with a TTL.
 */
@Singleton
class SpecHarvester {

    private static final Logger log = LoggerFactory.getLogger(SpecHarvester)
    private static final long TTL_MILLIS = 3600_000L
    private static final List<String> HTTP_METHODS = ["get", "post", "put", "delete", "patch"]

    // Captures the quoted spec path from swagger-ui/index.html, e.g. '/swagger/data-0.9.0.yml'
    private static final Pattern SPEC_REF = Pattern.compile(/'([^']*\/swagger\/[^']+\.(?:ya?ml|json))'/)

    private final JsonHttpClient http = new JsonHttpClient()
    private final Map<String, Map> cache = new ConcurrentHashMap<>()

    Map describe(String baseUrl) {
        String key = baseUrl?.replaceAll('/$', '')
        Map cached = cache.get(key)
        if (cached && (System.currentTimeMillis() - (cached._ts as long)) < TTL_MILLIS) {
            return cached.data as Map
        }
        Map data = harvest(key)
        cache.put(key, [_ts: System.currentTimeMillis(), data: data])
        return data
    }

    void clear() {
        cache.clear()
    }

    private Map harvest(String baseUrl) {
        String html = http.get("${baseUrl}/swagger-ui/index.html")
        String specPath = extractSpecPath(html)
        if (!specPath) {
            throw new IllegalStateException("Could not find an OpenAPI spec reference at ${baseUrl}/swagger-ui/index.html")
        }
        String specUrl = specPath.startsWith("http") ? specPath : "${baseUrl}${specPath}"
        String specText = http.get(specUrl)
        return summarize(baseUrl, new Yaml().load(specText) as Map)
    }

    static String extractSpecPath(String html) {
        Matcher m = SPEC_REF.matcher(html ?: "")
        return m.find() ? m.group(1) : null
    }

    static Map summarize(String baseUrl, Map spec) {
        Map info = (spec?.info ?: [:]) as Map
        Map paths = (spec?.paths ?: [:]) as Map
        List<Map> operations = []
        paths.each { path, methods ->
            if (methods instanceof Map) {
                (methods as Map).each { method, op ->
                    if (HTTP_METHODS.contains((method as String).toLowerCase()) && op instanceof Map) {
                        String summary = (op as Map).summary as String
                        operations << [
                                method : (method as String).toUpperCase(),
                                path   : path as String,
                                summary: summary,
                                secured: (summary ?: "").contains("Secure")
                        ]
                    }
                }
            }
        }
        operations.sort { "${it.path} ${it.method}" }
        return [
                baseUrl   : baseUrl,
                title     : info.title,
                version   : info.version,
                operations: operations
        ]
    }
}
