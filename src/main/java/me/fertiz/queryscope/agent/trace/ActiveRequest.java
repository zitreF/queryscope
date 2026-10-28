package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.calltree.CallTracer;
import me.fertiz.queryscope.agent.calltree.CallTree;
import me.fertiz.queryscope.agent.calltree.CallTreeBuilder;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ActiveRequest {

    private static final String TASK_METHOD = "TASK";
    private static final ClassLoadingMXBean CLASSES = ManagementFactory.getClassLoadingMXBean();
    private static final long BACKGROUND_IDLE_NANOS = TimeUnit.SECONDS.toNanos(1);
    static final int MAX_RECORDED_QUERIES = 1000;

    private final long id;
    private final UnitKind kind;
    private final String method;
    private final String path;
    private final boolean background;
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private final long classesAtStart = CLASSES.getTotalLoadedClassCount();
    private final long threadId = Thread.currentThread().getId();
    private final List<QueryRecord> queries = new ArrayList<>();
    private final CallTreeBuilder calls = CallTracer.unitStarted(startedNanos);
    private int queryDepth;
    private long queryStartedNanos;
    private long queryFinishedNanos = startedNanos;
    private int omittedQueries;
    private long omittedNanos;

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

    boolean isExpired(long nanos) {
        return queryDepth == 0
                && (nanos - queryFinishedNanos > BACKGROUND_IDLE_NANOS || !hasRoomForQueries());
    }

    boolean hasRoomForQueries() {
        return queries.size() < MAX_RECORDED_QUERIES;
    }

    void enterQuery() {
        if (queryDepth == 0) {
            queryStartedNanos = System.nanoTime();
        }
        queryDepth++;
    }

    boolean leaveOutermostQuery() {
        if (queryDepth == 0) {
            return false;
        }
        queryDepth--;
        if (queryDepth > 0) {
            return false;
        }
        queryFinishedNanos = System.nanoTime();
        return true;
    }

    QueryRecord addQuery(String sql, String parameters, boolean failed, String callSite) {
        QueryRecord query = new QueryRecord(sql, parameters, queryStartedNanos - startedNanos,
                queryFinishedNanos - queryStartedNanos, callSite, failed);
        queries.add(query);
        CallTracer.queryAdded(calls, queries.size() - 1);
        return query;
    }

    void omitQuery() {
        omittedQueries++;
        omittedNanos += queryFinishedNanos - queryStartedNanos;
    }

    long lastActivityNanos() {
        return Math.max(queryFinishedNanos, CallTracer.lastExitNanos(calls));
    }

    RequestTrace finish(int status, long endNanos) {
        CallTree callTree = CallTracer.unitFinished(calls, endNanos);
        List<QueryExecution> executions = new ArrayList<>(queries.size());
        for (QueryRecord query : queries) {
            executions.add(query.toExecution());
        }
        return new RequestTrace(
                id, kind, method, path, status, startedAt, endNanos - startedNanos,
                Collections.unmodifiableList(executions), callTree,
                CLASSES.getTotalLoadedClassCount() - classesAtStart, threadId, omittedQueries, omittedNanos);
    }
}
