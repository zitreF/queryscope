package me.fertiz.queryscope.agent.findings;

import me.fertiz.queryscope.agent.trace.QueryExecution;

public record LargeResult(int queryIndex, String normalizedSql, QueryExecution query) implements Finding {

    @Override
    public FindingKind kind() {
        return FindingKind.LARGE_RESULT;
    }
}
