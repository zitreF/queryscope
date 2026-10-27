package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.data.RequestTrace;
import me.fertiz.queryscope.agent.data.UnitKind;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class ActiveRequest {

    private static final String TASK_METHOD = "TASK";

    private final long id;
    private final UnitKind kind;
    private final String method;
    private final String path;
    private final boolean background;
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private final List<QueryRecord> queries = new ArrayList<>();
    private int queryDepth;
    private long queryStartedNanos;
    private long queryFinishedNanos = startedNanos;

    private ActiveRequest(long id, UnitKind kind, String method, String path, boolean background) {
        this.id = id;
        this.kind = kind;
        this.method = method;
        this.path = path;
        this.background = background;
    }

    static ActiveRequest http(long id, String method, String path) {
        return new ActiveRequest(id, UnitKind.HTTP, method, path, false);
    }

    static ActiveRequest entry(long id, String name) {
        return new ActiveRequest(id, UnitKind.TASK, TASK_METHOD, name, false);
    }

    static ActiveRequest background(long id, String name) {
        return new ActiveRequest(id, UnitKind.TASK, TASK_METHOD, name, true);
    }

    boolean isBackground() {
        return background;
    }

    boolean isBetweenQueries() {
        return queryDepth == 0;
    }

    boolean isIdleSince(long nanos, long idleNanos, int maxQueries) {
        return queryDepth == 0 && (nanos - queryFinishedNanos > idleNanos || queries.size() >= maxQueries);
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

    QueryRecord addQuery(String sql, String parameters, boolean failed, String callSite) {
        QueryRecord query = new QueryRecord(sql, parameters, queryStartedNanos - startedNanos,
                queryFinishedNanos - queryStartedNanos, callSite, failed);
        queries.add(query);
        return query;
    }

    RequestTrace finish(int status) {
        long endNanos = background ? queryFinishedNanos : System.nanoTime();
        return new RequestTrace(
                id, kind, method, path, status, startedAt, endNanos - startedNanos,
                queries.stream().map(QueryRecord::toExecution).toList());
    }
}
