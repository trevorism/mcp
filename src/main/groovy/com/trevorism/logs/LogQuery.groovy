package com.trevorism.logs

import groovy.transform.CompileStatic

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.regex.Matcher

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

    LogQuery at(Instant instant) {
        this.now = instant
        return this
    }

    static boolean isValidProject(String id) {
        return id != null && id.matches(/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/)
    }

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

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace('"', '\\"')
    }
}
