package me.fertiz.queryscope.agent.profiler;

public record Sample(long epochMillis, long threadId, boolean onCpu, boolean truncated, int[] frames) {
}
