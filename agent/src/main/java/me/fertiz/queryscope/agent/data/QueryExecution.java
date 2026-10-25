package me.fertiz.queryscope.agent.data;

public record QueryExecution(String sql, long offsetNanos, long durationNanos, String callSite, boolean failed) {
}
