package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.calltree.CallSites;
import me.fertiz.queryscope.agent.calltree.CallTracer;


import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class RequestTracer {

    private static final System.Logger LOGGER = System.getLogger("QueryScope");
    private static final ThreadLocal<ActiveRequest> CURRENT = new ThreadLocal<>();
    private static final AtomicLong IDS = new AtomicLong();
    private static final AtomicInteger OPEN_TASKS = new AtomicInteger();
    private static final WeakIdentityMap<StatementState> STATEMENTS = new WeakIdentityMap<>();
    private static final WeakIdentityMap<QueryRecord> RESULTS = new WeakIdentityMap<>();
    private static final int NO_STATUS = 0;

    private static volatile Consumer<RequestTrace> listener = trace -> {
    };

    private RequestTracer() {
    }

    public static void publishTo(Consumer<RequestTrace> listener) {
        RequestTracer.listener = listener;
    }

    public static int openTaskCount() {
        return OPEN_TASKS.get();
    }

    public static boolean inForegroundUnit() {
        ActiveRequest current = CURRENT.get();
        return current != null && !current.isBackground();
    }

    public static boolean requestStarted(String method, String uri, String queryString) {
        if (inForegroundUnit()) {
            return false;
        }
        finishTaskAtLastActivity();
        String path = queryString == null ? uri : uri + "?" + queryString;
        CURRENT.set(ActiveRequest.http(IDS.incrementAndGet(), method, path));
        return true;
    }

    public static void requestFinished(int status) {
        finishCurrent(status);
    }

    public static boolean taskStarted(String qualifiedName) {
        if (inForegroundUnit()) {
            return false;
        }
        finishTaskAtLastActivity();
        CURRENT.set(ActiveRequest.entry(IDS.incrementAndGet(), CallSites.shortName(qualifiedName)));
        return true;
    }

    public static void taskFinished() {
        finishCurrent(NO_STATUS);
    }

    public static void statementPrepared(Object statement, String sql) {
        if (statement != null && sql != null) {
            STATEMENTS.put(statement, new StatementState(sql));
        }
    }

    public static void parameterSet(Object statement, String setter, int index, Object value) {
        StatementState state = STATEMENTS.get(statement);
        if (state != null) {
            state.set(index, ParameterValues.describe(setter, value));
        }
    }

    public static void parametersCleared(Object statement) {
        StatementState state = STATEMENTS.get(statement);
        if (state != null) {
            state.clear();
        }
    }

    public static void rowRead(Object resultSet) {
        if (CURRENT.get() == null) {
            return;
        }
        QueryRecord query = RESULTS.get(resultSet);
        if (query != null) {
            query.rowRead();
        }
    }

    public static void queryStarted() {
        ActiveRequest current = CURRENT.get();
        if (current != null && current.isBackground() && current.isExpired(System.nanoTime())) {
            finishTaskAtLastActivity();
            current = null;
        }
        if (current == null) {
            current = ActiveRequest.background(IDS.incrementAndGet(), taskName());
            CURRENT.set(current);
            OPEN_TASKS.incrementAndGet();
        }
        current.enterQuery();
    }

    public static void queryFinished(String sql, boolean failed, Object result) {
        ActiveRequest request = CURRENT.get();
        if (request == null || !request.leaveOutermostQuery() || sql == null) {
            return;
        }
        if (request.hasRoomForQueries()) {
            watchRows(request.addQuery(sql, "", failed, CallSites.callSite()), result);
        } else {
            request.omitQuery();
        }
    }

    public static void preparedQueryFinished(Object statement, boolean failed, Object result) {
        ActiveRequest request = CURRENT.get();
        if (request == null || !request.leaveOutermostQuery()) {
            return;
        }
        StatementState state = STATEMENTS.get(statement);
        if (state == null) {
            return;
        }
        if (request.hasRoomForQueries()) {
            watchRows(request.addQuery(state.sql(), state.parameters(), failed, CallSites.callSite()), result);
        } else {
            request.omitQuery();
        }
    }

    public static void connectionReleased() {
        ActiveRequest current = CURRENT.get();
        if (current != null && current.isBackground() && current.isBetweenQueries()) {
            finishCurrent(NO_STATUS);
        }
    }

    private static String taskName() {
        return CallTracer.outermostMethod().map(CallSites::shortName).orElseGet(CallSites::entryName);
    }

    private static void watchRows(QueryRecord query, Object result) {
        if (result instanceof ResultSet) {
            query.resultOpened();
            RESULTS.put(result, query);
        }
    }

    private static void finishCurrent(int status) {
        ActiveRequest request = CURRENT.get();
        if (request != null) {
            finish(request, status, System.nanoTime());
        }
    }

    private static void finishTaskAtLastActivity() {
        ActiveRequest request = CURRENT.get();
        if (request != null) {
            finish(request, NO_STATUS, request.lastActivityNanos());
        }
    }

    private static void finish(ActiveRequest request, int status, long endNanos) {
        CURRENT.remove();
        if (request.isBackground()) {
            OPEN_TASKS.decrementAndGet();
        }
        RequestTrace trace = request.finish(status, endNanos);
        try {
            listener.accept(trace);
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not publish the trace of " + trace.path(), e);
        }
    }
}
