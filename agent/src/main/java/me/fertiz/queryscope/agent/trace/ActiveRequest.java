package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.data.QueryExecution;
import me.fertiz.queryscope.agent.data.RequestTrace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class ActiveRequest {

    private final long id;
    private final String method;
    private final String path;
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private final List<QueryExecution> queries = new ArrayList<>();
    private int queryDepth;
    private long queryStartedNanos;
    private long queryFinishedNanos;

    ActiveRequest(long id, String method, String path) {
        this.id = id;
        this.method = method;
        this.path = path;
    }

    void enterQuery() {
        if (queryDepth++ == 0) {
            queryStartedNanos = System.nanoTime();
        }
    }

    boolean exitQuery() {
        if (queryDepth == 0 || --queryDepth > 0) {
            return false;
        }
        queryFinishedNanos = System.nanoTime();
        return true;
    }

    void addQuery(String sql, boolean failed, String callSite) {
        queries.add(new QueryExecution(
                sql, queryStartedNanos - startedNanos, queryFinishedNanos - queryStartedNanos, callSite, failed));
    }

    RequestTrace finish(int status) {
        return new RequestTrace(
                id, method, path, status, startedAt, System.nanoTime() - startedNanos, List.copyOf(queries));
    }
}
