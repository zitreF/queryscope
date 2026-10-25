package me.fertiz.queryscope.agent.data;

import java.util.List;

public record NPlusOne(String normalizedSql, String callSite, List<Integer> queryIndexes, long totalNanos)
        implements Finding {

    public int executions() {
        return queryIndexes.size();
    }
}
