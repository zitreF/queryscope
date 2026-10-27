package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.data.QueryExecution;

final class QueryRecord {

    private final String sql;
    private final String parameters;
    private final long offsetNanos;
    private final long durationNanos;
    private final String callSite;
    private final boolean failed;
    private volatile int rows = QueryExecution.NO_RESULT;

    QueryRecord(String sql, String parameters, long offsetNanos, long durationNanos, String callSite, boolean failed) {
        this.sql = sql;
        this.parameters = parameters;
        this.offsetNanos = offsetNanos;
        this.durationNanos = durationNanos;
        this.callSite = callSite;
        this.failed = failed;
    }

    void resultOpened() {
        if (rows == QueryExecution.NO_RESULT) {
            rows = 0;
        }
    }

    void rowRead() {
        rows++;
    }

    QueryExecution toExecution() {
        return new QueryExecution(sql, parameters, offsetNanos, durationNanos, rows, callSite, failed);
    }
}
