package me.fertiz.queryscope.agent.findings;

public record SlowMethod(String method, long calls, long ownNanos, long durationNanos) implements Finding {
}
