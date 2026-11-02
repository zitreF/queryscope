package me.fertiz.queryscope.agent.trace;

public record QueryExecution(
        String sql,
        String parameters,
        long offsetNanos,
        long durationNanos,
        int rows,
        String callSite,
        boolean failed
) {

    public static final int NO_RESULT = -1;

    QueryExecution shiftedBy(long nanos) {
        return new QueryExecution(sql, parameters, offsetNanos + nanos, durationNanos, rows, callSite, failed);
    }
}
