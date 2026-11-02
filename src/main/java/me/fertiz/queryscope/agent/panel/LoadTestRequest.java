package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.loadtest.LoadTestPlan;

import java.util.Map;

final class LoadTestRequest {

    static final int DEFAULT_REQUESTS = 100;
    static final int DEFAULT_CONCURRENCY = 10;

    private LoadTestRequest() {
    }

    static LoadTestPlan plan(Map<?, ?> fields, ReportStore store) {
        if (!(fields.get("id") instanceof Long id)) {
            throw new IllegalArgumentException("id needs the number of a GET request, like 17");
        }
        UnitReport report = store.find(id).orElseThrow(() -> new IllegalArgumentException(
                "QueryScope keeps no request " + id + " any more, pick a newer one"));
        return LoadTestPlan.replaying(report.trace(), wholeNumber(fields, "requests", DEFAULT_REQUESTS),
                wholeNumber(fields, "concurrency", DEFAULT_CONCURRENCY));
    }

    private static int wholeNumber(Map<?, ?> fields, String name, int fallback) {
        Object value = fields.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Long number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " needs a whole number, got " + value);
        }
        return number.intValue();
    }
}
