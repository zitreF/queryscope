package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.data.RequestTrace;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class RequestTracer {

    private static final System.Logger LOGGER = System.getLogger("QueryScope");
    private static final ThreadLocal<ActiveRequest> CURRENT = new ThreadLocal<>();
    private static final AtomicLong IDS = new AtomicLong();
    private static final StatementSqlRegistry STATEMENTS = new StatementSqlRegistry();

    private static volatile Consumer<RequestTrace> listener = trace -> {
    };

    private RequestTracer() {
    }

    public static void publishTo(Consumer<RequestTrace> listener) {
        RequestTracer.listener = listener;
    }

    public static boolean requestStarted(String method, String uri, String queryString) {
        if (CURRENT.get() != null) {
            return false;
        }
        String path = queryString == null ? uri : uri + "?" + queryString;
        CURRENT.set(new ActiveRequest(IDS.incrementAndGet(), method, path));
        return true;
    }

    public static void requestFinished(int status) {
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

    public static void statementPrepared(Object statement, String sql) {
        if (statement != null && sql != null) {
            STATEMENTS.remember(statement, sql);
        }
    }

    public static void queryStarted() {
        ActiveRequest request = CURRENT.get();
        if (request != null) {
            request.enterQuery();
        }
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
}
