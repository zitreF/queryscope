package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.http.Json;
import me.fertiz.queryscope.agent.loadtest.LoadTestPlan;
import me.fertiz.queryscope.agent.trace.UnitTrace;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

final class LoadTestRequest {

    static final int DEFAULT_REQUESTS = 100;
    static final int DEFAULT_CONCURRENCY = 10;
    static final int MAX_JSON_BYTES = 4 * LoadTestPlan.MAX_BODY_BYTES;

    private LoadTestRequest() {
    }

    static LoadTestPlan plan(JsonNode fields, ReportStore store) {
        long id = Json.wholeNumber(fields.path("id")).orElseThrow(() -> new IllegalArgumentException(
                "id needs the number of an HTTP request, like 17"));
        UnitTrace trace = store.find(id).orElseThrow(() -> new IllegalArgumentException(
                "QueryScope keeps no request " + id + " any more, pick a newer one")).trace();
        return LoadTestPlan.replaying(trace, text(fields, "method", trace.method()), text(fields, "path", trace.path()),
                headers(fields.path("headers")), text(fields, "body", ""), wholeNumber(fields, "requests",
                        DEFAULT_REQUESTS), wholeNumber(fields, "concurrency", DEFAULT_CONCURRENCY));
    }

    private static String text(JsonNode fields, String name, String fallback) {
        JsonNode value = fields.path(name);
        if (Json.absent(value)) {
            return fallback;
        }
        if (!value.isString()) {
            throw new IllegalArgumentException(name + " needs to be text, got " + value);
        }
        return value.stringValue();
    }

    private static Map<String, String> headers(JsonNode value) {
        if (Json.absent(value)) {
            return Map.of();
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("headers needs an object like {\"Authorization\": \"Bearer ...\"}");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        value.properties().forEach(header -> {
            if (!header.getValue().isString()) {
                throw new IllegalArgumentException("The value of the header " + header.getKey() + " needs to be text");
            }
            headers.put(header.getKey(), header.getValue().stringValue());
        });
        return headers;
    }

    private static int wholeNumber(JsonNode fields, String name, int fallback) {
        JsonNode value = fields.path(name);
        if (Json.absent(value)) {
            return fallback;
        }
        OptionalLong number = Json.wholeNumber(value);
        if (number.isEmpty() || number.getAsLong() < Integer.MIN_VALUE || number.getAsLong() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " needs a whole number, got " + value);
        }
        return (int) number.getAsLong();
    }
}
