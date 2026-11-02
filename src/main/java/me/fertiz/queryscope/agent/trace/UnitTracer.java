package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.appcode.CallSites;
import me.fertiz.queryscope.agent.calltree.CallTracer;

import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class UnitTracer {

    public static final long NO_UNIT = 0;
    public static final int UNKNOWN_PORT = 0;
    public static final long NO_LOAD_TEST = 0;
    public static final String LOAD_TEST_HEADER = "X-QueryScope-Load-Test";

    private static final System.Logger LOGGER = System.getLogger("QueryScope");
    private static final ThreadLocal<ActiveUnit> CURRENT = new ThreadLocal<>();
    private static final AtomicLong IDS = new AtomicLong();
    private static final AtomicInteger OPEN_TASKS = new AtomicInteger();
    private static final WeakIdentityMap<BoundStatement> STATEMENTS = new WeakIdentityMap<>();
    private static final WeakIdentityMap<LiveQuery> RESULTS = new WeakIdentityMap<>();
    private static final WeakIdentityMap<PendingTask> ASYNC_TASKS = new WeakIdentityMap<>();
    private static final int NO_STATUS = 0;

    private static volatile Consumer<UnitTrace> listener = trace -> {
    };

    private UnitTracer() {
    }

    public static void publishTo(Consumer<UnitTrace> listener) {
        UnitTracer.listener = listener;
    }

    public static int openTaskCount() {
        return OPEN_TASKS.get();
    }

    public static long currentUnitId() {
        ActiveUnit current = CURRENT.get();
        return current == null ? NO_UNIT : current.id();
    }

    public static String currentUnitLabel() {
        ActiveUnit current = CURRENT.get();
        return current == null ? null : current.label();
    }

    public static boolean inRequestOrEntryTask() {
        ActiveUnit current = CURRENT.get();
        return current != null && !current.isStartedByQuery();
    }

    public static boolean requestStarted(String method, String uri, String queryString, int localPort,
                                         String loadTestHeader) {
        if (inRequestOrEntryTask()) {
            return false;
        }
        finishTaskAtLastActivity();
        String path = queryString == null ? uri : uri + "?" + queryString;
        CURRENT.set(ActiveUnit.http(IDS.incrementAndGet(), method, path, localPort, loadTestRunOf(loadTestHeader)));
        return true;
    }

    static long loadTestRunOf(String header) {
        if (header == null) {
            return NO_LOAD_TEST;
        }
        try {
            return Math.max(NO_LOAD_TEST, Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return NO_LOAD_TEST;
        }
    }

    public static void requestFinished(int status) {
        finishCurrentUnit(status);
    }

    public static boolean taskStarted(String qualifiedName) {
        if (inRequestOrEntryTask()) {
            return false;
        }
        finishTaskAtLastActivity();
        CURRENT.set(ActiveUnit.entry(IDS.incrementAndGet(), CallSites.classAndMethod(qualifiedName)));
        return true;
    }

    public static void taskFinished() {
        finishCurrentUnit(NO_STATUS);
    }

    static ActiveUnit asyncOwnerOfThisThread() {
        ActiveUnit current = CURRENT.get();
        return current == null || current.isStartedByQuery() ? null : current.owner();
    }

    static void asyncTaskSubmitted(Object task, ActiveUnit owner) {
        PendingTask replaced = ASYNC_TASKS.put(task, new PendingTask(owner, owner.attachAsyncTask()));
        if (replaced != null) {
            release(replaced);
        }
    }

    static boolean asyncTaskStarted(Object task) {
        if (ASYNC_TASKS.isEmpty()) {
            return false;
        }
        PendingTask pending = ASYNC_TASKS.remove(task);
        if (pending == null) {
            return false;
        }
        if (inRequestOrEntryTask()) {
            release(pending);
            return false;
        }
        finishTaskAtLastActivity();
        CURRENT.set(ActiveUnit.asyncTask(IDS.incrementAndGet(), pending.owner(), task, pending.counted()));
        return true;
    }

    static void asyncTaskFinished(Object task) {
        ActiveUnit current = CURRENT.get();
        if (current == null || !current.runs(task)) {
            return;
        }
        CURRENT.remove();
        UnitTrace trace = current.finish(NO_STATUS, System.nanoTime());
        UnitTrace kept = trace.queryCount() == 0 && trace.callTree().topLevelCalls().isEmpty() ? null : trace;
        if (current.countsForOwner()) {
            current.owner().attachedTaskEnded(kept, current.startedNanos()).forEach(UnitTracer::publish);
        } else if (kept != null) {
            publish(kept);
        }
    }

    public static void statementPrepared(Object statement, String sql) {
        if (statement != null && sql != null) {
            STATEMENTS.put(statement, new BoundStatement(sql));
        }
    }

    public static void parameterSet(Object statement, String setter, int index, Object value) {
        BoundStatement state = STATEMENTS.get(statement);
        if (state != null) {
            state.bind(index, ParameterValues.format(setter, value));
        }
    }

    public static void parametersCleared(Object statement) {
        BoundStatement state = STATEMENTS.get(statement);
        if (state != null) {
            state.clear();
        }
    }

    public static void rowRead(Object resultSet) {
        if (CURRENT.get() == null) {
            return;
        }
        LiveQuery query = RESULTS.get(resultSet);
        if (query != null) {
            query.rowRead();
        }
    }

    public static void queryStarted() {
        ActiveUnit current = CURRENT.get();
        if (current != null && current.isStartedByQuery() && current.isIdleOrFull(System.nanoTime())) {
            finishTaskAtLastActivity();
            current = null;
        }
        if (current == null) {
            current = ActiveUnit.startedByQuery(IDS.incrementAndGet(), taskName());
            CURRENT.set(current);
            OPEN_TASKS.incrementAndGet();
        }
        current.enterQuery();
    }

    public static void queryFinished(String sql, boolean failed, Object result) {
        ActiveUnit unit = CURRENT.get();
        if (unit == null || !unit.leaveOutermostQuery() || sql == null) {
            return;
        }
        if (unit.hasRoomForQueries()) {
            countRowsOf(unit.addQuery(sql, "", failed, CallSites.currentCallSite()), result);
        } else {
            unit.omitQuery();
        }
    }

    public static void preparedQueryFinished(Object statement, boolean failed, Object result) {
        ActiveUnit unit = CURRENT.get();
        if (unit == null || !unit.leaveOutermostQuery()) {
            return;
        }
        BoundStatement state = STATEMENTS.get(statement);
        if (state == null) {
            return;
        }
        if (unit.hasRoomForQueries()) {
            countRowsOf(unit.addQuery(state.sql(), state.parametersText(), failed, CallSites.currentCallSite()), result);
        } else {
            unit.omitQuery();
        }
    }

    public static void connectionReleased() {
        ActiveUnit current = CURRENT.get();
        if (current != null && current.isStartedByQuery() && current.isBetweenQueries()) {
            finishCurrentUnit(NO_STATUS);
        }
    }

    private static String taskName() {
        return CallTracer.outermostMethod().map(CallSites::classAndMethod).orElseGet(CallSites::nearestApplicationMethodOrThread);
    }

    private static void countRowsOf(LiveQuery query, Object result) {
        if (result instanceof ResultSet) {
            query.resultOpened();
            RESULTS.put(result, query);
        }
    }

    private static void finishCurrentUnit(int status) {
        ActiveUnit unit = CURRENT.get();
        if (unit != null) {
            finish(unit, status, System.nanoTime());
        }
    }

    private static void finishTaskAtLastActivity() {
        ActiveUnit unit = CURRENT.get();
        if (unit != null) {
            finish(unit, NO_STATUS, unit.lastActivityNanos());
        }
    }

    private static void finish(ActiveUnit unit, int status, long endNanos) {
        CURRENT.remove();
        if (unit.isStartedByQuery()) {
            OPEN_TASKS.decrementAndGet();
        }
        UnitTrace ready = unit.publishableTrace(unit.finish(status, endNanos));
        if (ready != null) {
            publish(ready);
        } else {
            AsyncWait.later(() -> {
                UnitTrace late = unit.asyncWaitEnded();
                if (late != null) {
                    publish(late);
                }
            });
        }
    }

    private static void release(PendingTask pending) {
        if (pending.counted()) {
            pending.owner().attachedTaskEnded(null, 0).forEach(UnitTracer::publish);
        }
    }

    private record PendingTask(ActiveUnit owner, boolean counted) {
    }

    private static void publish(UnitTrace trace) {
        try {
            listener.accept(trace);
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not publish the trace of " + trace.path(), e);
        }
    }
}
