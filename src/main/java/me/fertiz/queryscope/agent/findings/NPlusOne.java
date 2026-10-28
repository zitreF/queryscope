package me.fertiz.queryscope.agent.findings;

import java.util.List;

public record NPlusOne(String normalizedSql, String callSite, List<Integer> queryIndexes, long totalNanos)
        implements Finding {

    @Override
    public FindingKind kind() {
        return FindingKind.N_PLUS_ONE;
    }

    public int executions() {
        return queryIndexes.size();
    }
}
