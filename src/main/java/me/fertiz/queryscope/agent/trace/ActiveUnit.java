package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.calltree.AsyncCalls;
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
    private final int localPort;
    private final long loadTestRun;
    private final ActiveUnit owner;
    private final Object asyncTask;
    private final boolean countsForOwner;
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
    private String label;
    private final List<AsyncPart> asyncParts = new ArrayList<>();
    private int runningAsyncTasks;
    private UnitTrace waitingTrace;
    private boolean published;

    private ActiveUnit(long id, UnitKind kind, String method, String path, boolean startedByQuery, int localPort,
                       long loadTestRun, ActiveUnit owner, Object asyncTask, boolean countsForOwner) {
        this.id = id;
        this.kind = kind;
        this.method = method;
        this.path = path;
        this.startedByQuery = startedByQuery;
        this.localPort = localPort;
        this.loadTestRun = loadTestRun;
        this.owner = owner;
        this.asyncTask = asyncTask;
        this.countsForOwner = countsForOwner;
    }

    static ActiveUnit http(long id, String method, String path, int localPort, long loadTestRun) {
        return new ActiveUnit(id, UnitKind.HTTP, method, path, false, localPort, loadTestRun, null, null, false);
    }

    static ActiveUnit entry(long id, String name) {
        return new ActiveUnit(id, UnitKind.TASK, TASK_METHOD, name, false, UnitTracer.UNKNOWN_PORT,
                UnitTracer.NO_LOAD_TEST, null, null, false);
    }

    static ActiveUnit startedByQuery(long id, String name) {
        return new ActiveUnit(id, UnitKind.TASK, TASK_METHOD, name, true, UnitTracer.UNKNOWN_PORT,
                UnitTracer.NO_LOAD_TEST, null, null, false);
    }

    static ActiveUnit asyncTask(long id, ActiveUnit owner, Object task, boolean countsForOwner) {
        return new ActiveUnit(id, UnitKind.TASK, TASK_METHOD, "Async from " + owner.label(), false,
                UnitTracer.UNKNOWN_PORT, owner.loadTestRun, owner, task, countsForOwner);
    }

    long id() {
        return id;
    }

    String label() {
        if (label == null) {
            label = kind == UnitKind.HTTP ? method + " " + path : "Task " + path;
        }
        return label;
    }

    boolean isStartedByQuery() {
        return startedByQuery;
    }

    ActiveUnit owner() {
        return owner == null ? this : owner;
    }

    boolean runs(Object task) {
        return asyncTask != null && asyncTask == task;
    }

    long startedNanos() {
        return startedNanos;
    }

    boolean countsForOwner() {
        return countsForOwner;
    }

    synchronized UnitTrace publishableTrace(UnitTrace own) {
        if (runningAsyncTasks == 0) {
            published = true;
            return withAsyncParts(own);
        }
        waitingTrace = own;
        return null;
    }

    synchronized List<UnitTrace> attachedTaskEnded(UnitTrace task, long taskStartedNanos) {
        runningAsyncTasks--;
        if (published) {
            return task == null ? List.of() : List.of(task);
        }
        if (task != null) {
            asyncParts.add(new AsyncPart(task, taskStartedNanos - startedNanos));
        }
        if (waitingTrace == null || runningAsyncTasks > 0) {
            return List.of();
        }
        published = true;
        return List.of(withAsyncParts(waitingTrace));
    }

    synchronized UnitTrace asyncWaitEnded() {
        if (published || waitingTrace == null) {
            return null;
        }
        published = true;
        return withAsyncParts(waitingTrace);
    }

    synchronized boolean attachAsyncTask() {
        if (published) {
            return false;
        }
        runningAsyncTasks++;
        return true;
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
                CLASSES.getTotalLoadedClassCount() - classesAtStart, threadId, omittedQueries, omittedNanos, localPort,
                loadTestRun);
    }

    private UnitTrace withAsyncParts(UnitTrace own) {
        if (asyncParts.isEmpty()) {
            return own;
        }
        List<QueryExecution> executions = new ArrayList<>(own.queries());
        int omitted = own.omittedQueries();
        long omittedTime = own.omittedNanos();
        List<AsyncCalls> asyncCalls = new ArrayList<>(asyncParts.size());
        for (AsyncPart part : asyncParts) {
            UnitTrace task = part.trace();
            int firstIndex = executions.size();
            int kept = Math.max(0, Math.min(task.queries().size(), MAX_RECORDED_QUERIES - firstIndex));
            for (int index = 0; index < task.queries().size(); index++) {
                QueryExecution query = task.queries().get(index);
                if (index < kept) {
                    executions.add(query.shiftedBy(part.offsetNanos()));
                } else {
                    omitted++;
                    omittedTime += query.durationNanos();
                }
            }
            omitted += task.omittedQueries();
            omittedTime += task.omittedNanos();
            asyncCalls.add(new AsyncCalls(task.callTree(), task.durationNanos(), firstIndex, kept));
        }
        CallTree callTree = calls == null ? own.callTree() : own.callTree().withAsyncCalls(asyncCalls);
        return new UnitTrace(own.id(), own.kind(), own.method(), own.path(), own.status(), own.startedAt(),
                own.durationNanos(), Collections.unmodifiableList(executions), callTree, own.classesLoaded(),
                own.threadId(), omitted, omittedTime, own.localPort(), own.loadTestRun());
    }

    private record AsyncPart(UnitTrace trace, long offsetNanos) {
    }
}
