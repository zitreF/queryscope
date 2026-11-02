package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.loadtest.LoadTestPlan;
import me.fertiz.queryscope.agent.trace.UnitTrace;

import java.util.LinkedHashMap;
import java.util.Map;

final class LoadTestRequest {

    static final int DEFAULT_REQUESTS = 100;
    static final int DEFAULT_CONCURRENCY = 10;

    private LoadTestRequest() {
    }

    static LoadTestPlan plan(Map<?, ?> fields, ReportStore store) {
        if (!(fields.get("id") instanceof Long id)) {
            throw new IllegalArgumentException("id needs the number of an HTTP request, like 17");
        }
        UnitTrace trace = store.find(id).orElseThrow(() -> new IllegalArgumentException(
                "QueryScope keeps no request " + id + " any more, pick a newer one")).trace();
        return LoadTestPlan.replaying(trace, text(fields, "method", trace.method()), text(fields, "path", trace.path()),
                headers(fields.get("headers")), text(fields, "body", ""), wholeNumber(fields, "requests",
                        DEFAULT_REQUESTS), wholeNumber(fields, "concurrency", DEFAULT_CONCURRENCY));
    }

    private static String text(Map<?, ?> fields, String name, String fallback) {
        Object value = fields.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(name + " needs to be text, got " + value);
        }
        return text;
    }

    private static Map<String, String> headers(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> fields)) {
            throw new IllegalArgumentException("headers needs an object like {\"Authorization\": \"Bearer ...\"}");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        fields.forEach((name, text) -> {
            if (!(text instanceof String header)) {
                throw new IllegalArgumentException("The value of the header " + name + " needs to be text");
            }
            headers.put((String) name, header);
        });
        return headers;
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
