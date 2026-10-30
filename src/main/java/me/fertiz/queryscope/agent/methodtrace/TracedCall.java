package me.fertiz.queryscope.agent.methodtrace;

public record TracedCall(long endedAtMillis, long durationNanos, boolean failed, String thread, long unitId,
                         String unit) {
}
