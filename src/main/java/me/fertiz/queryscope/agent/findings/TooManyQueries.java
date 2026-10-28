package me.fertiz.queryscope.agent.findings;

public record TooManyQueries(int queries, int limit, long databaseNanos, long durationNanos) implements Finding {
}
