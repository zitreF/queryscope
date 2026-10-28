package me.fertiz.queryscope.agent.findings;

import java.util.List;

public record RepeatedWrite(String normalizedSql, String callSite, List<Integer> queryIndexes, long totalNanos)
        implements Finding {

    @Override
    public FindingKind kind() {
        return FindingKind.REPEATED_WRITE;
    }

    public int executions() {
        return queryIndexes.size();
    }
}
