package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.appcode.CallSites;
import me.fertiz.queryscope.agent.calltree.CallTracer;


import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class UnitTracer {

    private static final System.Logger LOGGER = System.getLogger("QueryScope");
    private static final ThreadLocal<ActiveUnit> CURRENT = new ThreadLocal<>();
    private static final AtomicLong IDS = new AtomicLong();
    private static final AtomicInteger OPEN_TASKS = new AtomicInteger();
    private static final WeakIdentityMap<BoundStatement> STATEMENTS = new WeakIdentityMap<>();
    private static final WeakIdentityMap<LiveQuery> RESULTS = new WeakIdentityMap<>();
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

    public static boolean inRequestOrEntryTask() {
        ActiveUnit current = CURRENT.get();
        return current != null && !current.isStartedByQuery();
    }

    public static boolean requestStarted(String method, String uri, String queryString) {
        if (inRequestOrEntryTask()) {
            return false;
        }
        finishTaskAtLastActivity();
        String path = queryString == null ? uri : uri + "?" + queryString;
        CURRENT.set(ActiveUnit.http(IDS.incrementAndGet(), method, path));
        return true;
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
        UnitTrace trace = unit.finish(status, endNanos);
        try {
            listener.accept(trace);
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not publish the trace of " + trace.path(), e);
        }
    }
}
