package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.data.RequestTrace;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class RequestTracer {

    private static final System.Logger LOGGER = System.getLogger("QueryScope");
    private static final ThreadLocal<ActiveRequest> CURRENT = new ThreadLocal<>();
    private static final AtomicLong IDS = new AtomicLong();
    private static final StatementSqlRegistry STATEMENTS = new StatementSqlRegistry();
    private static final long BACKGROUND_IDLE_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int BACKGROUND_MAX_QUERIES = 1000;
    private static final int NO_STATUS = 0;

    private static volatile Consumer<RequestTrace> listener = trace -> {
    };

    private RequestTracer() {
    }

    public static void publishTo(Consumer<RequestTrace> listener) {
        RequestTracer.listener = listener;
    }

    public static boolean inForegroundUnit() {
        ActiveRequest current = CURRENT.get();
        return current != null && !current.isBackground();
    }

    public static boolean requestStarted(String method, String uri, String queryString) {
        if (!makeRoomForForegroundUnit()) {
            return false;
        }
        String path = queryString == null ? uri : uri + "?" + queryString;
        CURRENT.set(ActiveRequest.http(IDS.incrementAndGet(), method, path));
        return true;
    }

    public static void requestFinished(int status) {
        finishCurrent(status);
    }

    public static boolean taskStarted(String qualifiedName) {
        if (!makeRoomForForegroundUnit()) {
            return false;
        }
        CURRENT.set(ActiveRequest.entry(IDS.incrementAndGet(), CallSites.shortName(qualifiedName)));
        return true;
    }

    public static void taskFinished() {
        finishCurrent(NO_STATUS);
    }

    public static void statementPrepared(Object statement, String sql) {
        if (statement != null && sql != null) {
            STATEMENTS.remember(statement, sql);
        }
    }

    public static void queryStarted() {
        ActiveRequest current = CURRENT.get();
        if (current != null && current.isBackground()
                && current.isIdleSince(System.nanoTime(), BACKGROUND_IDLE_NANOS, BACKGROUND_MAX_QUERIES)) {
            finishCurrent(NO_STATUS);
            current = null;
        }
        if (current == null) {
            current = ActiveRequest.background(IDS.incrementAndGet(), CallSites.entryName());
            CURRENT.set(current);
        }
        current.enterQuery();
    }

    public static void queryFinished(String sql, boolean failed) {
        ActiveRequest request = CURRENT.get();
        if (request != null && request.exitQuery() && sql != null) {
            request.addQuery(sql, failed, CallSites.current());
        }
    }

    public static void preparedQueryFinished(Object statement, boolean failed) {
        ActiveRequest request = CURRENT.get();
        if (request != null && request.exitQuery()) {
            String sql = STATEMENTS.sqlOf(statement);
            if (sql != null) {
                request.addQuery(sql, failed, CallSites.current());
            }
        }
    }

    public static void connectionReleased() {
        ActiveRequest current = CURRENT.get();
        if (current != null && current.isBackground() && current.isBetweenQueries()) {
            finishCurrent(NO_STATUS);
        }
    }

    private static boolean makeRoomForForegroundUnit() {
        ActiveRequest current = CURRENT.get();
        if (current == null) {
            return true;
        }
        if (!current.isBackground()) {
            return false;
        }
        finishCurrent(NO_STATUS);
        return true;
    }

    private static void finishCurrent(int status) {
        ActiveRequest request = CURRENT.get();
        if (request == null) {
            return;
        }
        CURRENT.remove();
        RequestTrace trace = request.finish(status);
        try {
            listener.accept(trace);
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not publish the trace of " + trace.path(), e);
        }
    }
}
