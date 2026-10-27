package me.fertiz.queryscope.agent.data;

public record TooManyQueries(int queries, int limit, long databaseNanos, long durationNanos) implements Finding {
}
