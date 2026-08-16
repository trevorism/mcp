package com.trevorism.logs

import groovy.transform.CompileStatic

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.regex.Matcher

/**
 * Turns MCP tool arguments into a Cloud Logging entries:list request. Pure — no I/O — so the filter
 * language, which is easy to get subtly wrong, is fully unit testable.
 *
 * Note that no logName is pinned: App Engine writes application output to `stdout`/`stderr` and request
 * summaries to `appengine.googleapis.com/request_log`, and a query that named one would silently miss
 * the other.
 */
@CompileStatic
class LogQuery {

    static final List<String> SEVERITIES =
            ["DEFAULT", "DEBUG", "INFO", "NOTICE", "WARNING", "ERROR", "CRITICAL", "ALERT", "EMERGENCY"]

    private static final int DEFAULT_LIMIT = 50
    private static final int MAX_LIMIT = 500
    private static final String DEFAULT_SINCE = "1h"
    private static final long MAX_SINCE_DAYS = 30L

    String project
    String module
    String severity
    String since = DEFAULT_SINCE
    String contains
    String rawFilter
    int limit = DEFAULT_LIMIT

    private Instant now = Instant.now()

    /** Test seam so a query's timestamp bound is deterministic. */
    LogQuery at(Instant instant) {
        this.now = instant
        return this
    }

    static boolean isValidProject(String id) {
        return id != null && id.matches(/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/)
    }

    /** The entries:list request body. Throws IllegalArgumentException on unusable arguments. */
    Map toRequest() {
        if (!isValidProject(project)) {
            throw new IllegalArgumentException("Invalid GCP project id: '${project}'")
        }
        return [
                resourceNames: ["projects/${project}".toString()],
                filter       : buildFilter(),
                orderBy      : "timestamp desc",
                pageSize     : clampedLimit()
        ]
    }

    int clampedLimit() {
        int requested = limit <= 0 ? DEFAULT_LIMIT : limit
        return Math.min(requested, MAX_LIMIT)
    }

    String buildFilter() {
        List<String> clauses = ['resource.type="gae_app"']
        if (module) {
            clauses.add("resource.labels.module_id=\"${escape(module)}\"".toString())
        }
        if (severity) {
            clauses.add("severity>=${normalizedSeverity()}".toString())
        }
        clauses.add("timestamp>=\"${startTimestamp()}\"".toString())
        if (contains) {
            clauses.add("\"${escape(contains)}\"".toString())
        }
        if (rawFilter?.trim()) {
            clauses.add("(${rawFilter.trim()})".toString())
        }
        return clauses.join(" AND ")
    }

    String normalizedSeverity() {
        String candidate = severity?.trim()?.toUpperCase()
        if (!SEVERITIES.contains(candidate)) {
            throw new IllegalArgumentException("Unknown severity '${severity}'. Use one of ${SEVERITIES.join(", ")}.")
        }
        return candidate
    }

    String startTimestamp() {
        return DateTimeFormatter.ISO_INSTANT.format(now.minus(sinceDuration()))
    }

    private Duration sinceDuration() {
        String value = (since ?: DEFAULT_SINCE).trim().toLowerCase()
        Matcher matcher = value =~ /^(\d+)([smhd])$/
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid 'since' value '${since}'. Use e.g. 30m, 2h, 3d.")
        }
        long amount = Long.parseLong(matcher.group(1))
        Duration duration = durationOf(amount, matcher.group(2))
        if (duration.toDays() > MAX_SINCE_DAYS) {
            throw new IllegalArgumentException("'since' cannot exceed ${MAX_SINCE_DAYS}d.")
        }
        return duration
    }

    private static Duration durationOf(long amount, String unit) {
        switch (unit) {
            case "s": return Duration.ofSeconds(amount)
            case "m": return Duration.ofMinutes(amount)
            case "h": return Duration.ofHours(amount)
            default: return Duration.ofDays(amount)
        }
    }

    /** The filter is a query language, so caller-supplied values are quoted, never concatenated raw. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace('"', '\\"')
    }
}
