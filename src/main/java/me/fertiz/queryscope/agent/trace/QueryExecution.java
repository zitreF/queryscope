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
}
