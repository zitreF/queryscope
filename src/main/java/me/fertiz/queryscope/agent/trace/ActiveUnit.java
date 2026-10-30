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

final class ActiveUnit {

    private static final String TASK_METHOD = "TASK";
    private static final ClassLoadingMXBean CLASSES = ManagementFactory.getClassLoadingMXBean();
    private static final long BACKGROUND_IDLE_NANOS = TimeUnit.SECONDS.toNanos(1);
    static final int MAX_RECORDED_QUERIES = 1000;

    private final long id;
    private final UnitKind kind;
    private final String method;
    private final String path;
    private final boolean startedByQuery;
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private final long classesAtStart = CLASSES.getTotalLoadedClassCount();
    private final long threadId = Thread.currentThread().getId();
    private final List<LiveQuery> queries = new ArrayList<>();
    private final CallTreeBuilder calls = CallTracer.unitStarted(startedNanos);
    private int queryDepth;
    private long queryStartedNanos;
    private long queryFinishedNanos = startedNanos;
    private int omittedQueries;
    private long omittedNanos;

    private ActiveUnit(long id, UnitKind kind, String method, String path, boolean startedByQuery) {
        this.id = id;
        this.kind = kind;
        this.method = method;
        this.path = path;
        this.startedByQuery = startedByQuery;
    }

    static ActiveUnit http(long id, String method, String path) {
        return new ActiveUnit(id, UnitKind.HTTP, method, path, false);
    }

    static ActiveUnit entry(long id, String name) {
        return new ActiveUnit(id, UnitKind.TASK, TASK_METHOD, name, false);
    }

    static ActiveUnit startedByQuery(long id, String name) {
        return new ActiveUnit(id, UnitKind.TASK, TASK_METHOD, name, true);
    }

    boolean isStartedByQuery() {
        return startedByQuery;
    }

    boolean isBetweenQueries() {
        return queryDepth == 0;
    }

    boolean isIdleOrFull(long nanos) {
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

    LiveQuery addQuery(String sql, String parameters, boolean failed, String callSite) {
        LiveQuery query = new LiveQuery(sql, parameters, queryStartedNanos - startedNanos,
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

    UnitTrace finish(int status, long endNanos) {
        CallTree callTree = CallTracer.unitFinished(calls, endNanos);
        List<QueryExecution> executions = new ArrayList<>(queries.size());
        for (LiveQuery query : queries) {
            executions.add(query.snapshot());
        }
        return new UnitTrace(
                id, kind, method, path, status, startedAt, endNanos - startedNanos,
                Collections.unmodifiableList(executions), callTree,
                CLASSES.getTotalLoadedClassCount() - classesAtStart, threadId, omittedQueries, omittedNanos);
    }
}
