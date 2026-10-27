package me.fertiz.queryscope.agent.data;

public record LargeResult(int queryIndex, QueryExecution query) implements Finding {
}
