package me.fertiz.queryscope.agent.loadtest;

import me.fertiz.queryscope.agent.trace.UnitKind;
import me.fertiz.queryscope.agent.trace.UnitTrace;
import me.fertiz.queryscope.agent.trace.UnitTracer;

import java.net.URI;

public record LoadTestPlan(String endpoint, URI target, int requests, int concurrency) {

    public static final int MAX_REQUESTS = 10_000;
    public static final int MAX_CONCURRENCY = 50;

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
    }

    public static LoadTestPlan replaying(UnitTrace trace, int requests, int concurrency) {
        if (trace.kind() != UnitKind.HTTP) {
            throw new IllegalArgumentException(trace.endpoint() + " is no HTTP request, so it cannot be sent again");
        }
        if (!"GET".equals(trace.method())) {
            throw new IllegalArgumentException("QueryScope load tests GET requests only, request " + trace.id()
                    + " was a " + trace.method());
        }
        if (trace.localPort() == UnitTracer.UNKNOWN_PORT) {
            throw new IllegalArgumentException("QueryScope does not know the port request " + trace.id()
                    + " came in on, so it cannot send it again");
        }
        return new LoadTestPlan(trace.endpoint(), loopbackTarget(trace.localPort(), trace.path()), requests,
                concurrency);
    }

    public int workers() {
        return Math.min(requests, concurrency);
    }

    private static URI loopbackTarget(int port, String path) {
        URI target;
        try {
            target = URI.create("http://" + LOOPBACK + ":" + port + path);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("QueryScope cannot send " + path + " again: " + e.getMessage());
        }
        if (!path.startsWith("/") || !LOOPBACK.equals(target.getHost()) || target.getPort() != port) {
            throw new IllegalArgumentException("QueryScope cannot send " + path + " again to port " + port);
        }
        return target;
    }
}
