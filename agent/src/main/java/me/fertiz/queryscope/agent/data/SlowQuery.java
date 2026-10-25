package me.fertiz.queryscope.agent.data;

public record SlowQuery(int queryIndex, QueryExecution query) implements Finding {
}
