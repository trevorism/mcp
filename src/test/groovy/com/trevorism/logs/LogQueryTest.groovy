package com.trevorism.logs

import org.junit.jupiter.api.Test

import java.time.Instant

class LogQueryTest {

    private static final Instant NOON = Instant.parse("2026-08-16T12:00:00Z")

    private static LogQuery query(Map props = [:]) {
        new LogQuery([project: "trevorism-data"] + props).at(NOON)
    }

    @Test
    void testMinimalRequest() {
        Map request = query().toRequest()
        assert request.resourceNames == ["projects/trevorism-data"]
        assert request.orderBy == "timestamp desc"
        assert request.pageSize == 50
        assert request.filter == 'resource.type="gae_app" AND timestamp>="2026-08-16T11:00:00Z"'
    }

    @Test
    void testFilterCombinesEveryClause() {
        String filter = query([module: "event", severity: "error", since: "2h",
                               contains: "NullPointer", rawFilter: 'logName:"stderr"']).buildFilter()
        assert filter == 'resource.type="gae_app" AND resource.labels.module_id="event" AND severity>=ERROR ' +
                'AND timestamp>="2026-08-16T10:00:00Z" AND "NullPointer" AND (logName:"stderr")'
    }

    @Test
    void testSinceUnits() {
        assert query([since: "30s"]).startTimestamp() == "2026-08-16T11:59:30Z"
        assert query([since: "45m"]).startTimestamp() == "2026-08-16T11:15:00Z"
        assert query([since: "3h"]).startTimestamp() == "2026-08-16T09:00:00Z"
        assert query([since: "2d"]).startTimestamp() == "2026-08-14T12:00:00Z"
    }

    @Test
    void testInvalidSinceIsRejected() {
        def e = shouldFail { query([since: "yesterday"]).buildFilter() }
        assert e.message.contains("Invalid 'since'")
        assert shouldFail { query([since: "90d"]).buildFilter() }.message.contains("cannot exceed")
    }

    @Test
    void testUnknownSeverityIsRejected() {
        assert shouldFail { query([severity: "LOUD"]).buildFilter() }.message.contains("Unknown severity")
    }

    @Test
    void testQuotesAndBackslashesAreEscaped() {
        // A filter is a query language; an unescaped quote would let an argument close the literal
        // and append clauses of its own.
        String filter = query([module: 'a" OR severity>=EMERGENCY OR "x', contains: 'back\\slash']).buildFilter()
        assert filter.contains('resource.labels.module_id="a\\" OR severity>=EMERGENCY OR \\"x"')
        assert filter.contains('"back\\\\slash"')
    }

    @Test
    void testLimitIsClamped() {
        assert query([limit: 5000]).clampedLimit() == 500
        assert query([limit: 0]).clampedLimit() == 50
        assert query([limit: -3]).clampedLimit() == 50
        assert query([limit: 120]).clampedLimit() == 120
    }

    @Test
    void testProjectValidation() {
        assert LogQuery.isValidProject("trevorism-data")
        assert !LogQuery.isValidProject(null)
        assert !LogQuery.isValidProject("Trevorism")
        assert !LogQuery.isValidProject("../../etc")
        assert !LogQuery.isValidProject("proj/locations/x")
        assert shouldFail { query([project: "bad id"]).toRequest() }.message.contains("Invalid GCP project id")
    }

    private static Exception shouldFail(Closure closure) {
        try {
            closure.call()
        } catch (Exception e) {
            return e
        }
        throw new AssertionError("Expected an exception but none was thrown" as Object)
    }
}
