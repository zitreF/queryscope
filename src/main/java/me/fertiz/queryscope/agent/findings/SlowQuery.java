package me.fertiz.queryscope.agent.findings;

import me.fertiz.queryscope.agent.trace.QueryExecution;

public record SlowQuery(int queryIndex, String normalizedSql, QueryExecution query) implements Finding {
}
