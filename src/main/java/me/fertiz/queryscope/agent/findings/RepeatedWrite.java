package me.fertiz.queryscope.agent.findings;

import java.util.List;

public record RepeatedWrite(String normalizedSql, String callSite, List<Integer> queryIndexes, long totalNanos)
        implements Finding {

    public int executions() {
        return queryIndexes.size();
    }
}
