package me.fertiz.queryscope.agent.loadtest;

import me.fertiz.queryscope.agent.trace.UnitKind;
import me.fertiz.queryscope.agent.trace.UnitTrace;
import me.fertiz.queryscope.agent.trace.UnitTracer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public record LoadTestPlan(String endpoint, URI target, String method, Map<String, String> headers, String body,
                           int requests, int concurrency) {

    public static final int MAX_REQUESTS = 10_000;
    public static final int MAX_CONCURRENCY = 50;
    public static final List<String> METHODS = List.of("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS");
    public static final int MAX_HEADERS = 20;
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private static final List<String> METHODS_WITH_BODY = List.of("POST", "PUT");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}");
    private static final int MAX_HEADER_VALUE = 4096;
    private static final String LOOPBACK = "127.0.0.1";

    public LoadTestPlan {
        if (requests < 1 || requests > MAX_REQUESTS) {
            throw new IllegalArgumentException("requests needs a whole number from 1 to " + MAX_REQUESTS
                    + ", got " + requests);
        }
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new IllegalArgumentException("concurrency needs a whole number from 1 to " + MAX_CONCURRENCY
                    + ", got " + concurrency);
        }
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("QueryScope load tests " + String.join(", ", METHODS)
                    + " requests, got " + method);
        }
        checkHeaders(headers);
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        body = body == null ? "" : body;
        if (!body.isEmpty() && !METHODS_WITH_BODY.contains(method)) {
            throw new IllegalArgumentException("A body can only go with " + String.join(" or ", METHODS_WITH_BODY)
                    + ", not with " + method);
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("The body can have at most " + MAX_BODY_BYTES / 1024 + " KB");
        }
    }

    public static LoadTestPlan replaying(UnitTrace trace, int requests, int concurrency) {
        return replaying(trace, trace.method(), trace.path(), Map.of(), "", requests, concurrency);
    }

    public static LoadTestPlan replaying(UnitTrace trace, String method, String path, Map<String, String> headers,
                                         String body, int requests, int concurrency) {
        if (trace.kind() != UnitKind.HTTP) {
            throw new IllegalArgumentException(trace.endpoint() + " is no HTTP request, so it cannot be sent again");
        }
        if (trace.localPort() == UnitTracer.UNKNOWN_PORT) {
            throw new IllegalArgumentException("QueryScope does not know the port request " + trace.id()
                    + " came in on, so it cannot send it again");
        }
        String upperMethod = method.trim().toUpperCase(Locale.ROOT);
        return new LoadTestPlan(UnitTrace.endpointOf(upperMethod, path), loopbackTarget(trace.localPort(), path),
                upperMethod, headers, body, requests, concurrency);
    }

    public int workers() {
        return Math.min(requests, concurrency);
    }

    public String path() {
        return target.getRawQuery() == null ? target.getRawPath() : target.getRawPath() + "?" + target.getRawQuery();
    }

    public byte[] bodyBytes() {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static void checkHeaders(Map<String, String> headers) {
        if (headers.size() > MAX_HEADERS) {
            throw new IllegalArgumentException("A load test can send at most " + MAX_HEADERS + " headers");
        }
        headers.forEach((name, value) -> {
            if (name == null || !HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("'" + name + "' is no valid header name");
            }
            if (name.equalsIgnoreCase(UnitTracer.LOAD_TEST_HEADER)) {
                throw new IllegalArgumentException(UnitTracer.LOAD_TEST_HEADER + " is set by QueryScope itself");
            }
            if (value == null || value.length() > MAX_HEADER_VALUE || value.indexOf('\r') >= 0
                    || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("The value of " + name + " needs one line of at most "
                        + MAX_HEADER_VALUE + " characters");
            }
        });
    }

    private static URI loopbackTarget(int port, String path) {
        URI target;
        try {
            target = URI.create("http://" + LOOPBACK + ":" + port + path);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("QueryScope cannot send " + path + ": " + e.getMessage());
        }
        if (!path.startsWith("/") || !LOOPBACK.equals(target.getHost()) || target.getPort() != port) {
            throw new IllegalArgumentException("QueryScope cannot send " + path + " to port " + port
                    + ", the path has to start with /");
        }
        return target;
    }
}
