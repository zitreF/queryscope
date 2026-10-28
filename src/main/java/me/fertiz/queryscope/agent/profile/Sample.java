package me.fertiz.queryscope.agent.profile;

public record Sample(long epochMillis, long threadId, boolean onCpu, boolean truncated, int[] frames) {
}
