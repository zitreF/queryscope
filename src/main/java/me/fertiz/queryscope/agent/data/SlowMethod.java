package me.fertiz.queryscope.agent.data;

public record SlowMethod(String method, long calls, long ownNanos, long durationNanos) implements Finding {
}
